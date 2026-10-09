package org.yamcs.mqtt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.tctm.Link.Status;
import org.yamcs.utils.TimeEncoding;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.util.concurrent.GlobalEventExecutor;

public class MqttPacketLinkTest {

    static NioEventLoopGroup bossGroup;
    static NioEventLoopGroup workerGroup;
    static Channel serverChannel;
    static EventProducer eventProducer = mock(EventProducer.class);

    FakeMqttBroker broker;

    @BeforeAll
    public static void setup() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        // the mockup event producer timestamps its events
        TimeEncoding.setUp();
        EventProducerFactory.setMockup(true);
    }

    @AfterAll
    public static void teardown() throws InterruptedException {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    @BeforeEach
    public void beforeEach() throws InterruptedException {
        broker = new FakeMqttBroker();
    }

    @AfterEach
    public void afterEach() throws InterruptedException {
        broker.stop();
    }

    @Test
    public void testConnectionRefused() throws Exception {
        broker.stop();
        var mpt = getLink(false, null);
        mpt.startAsync().awaitRunning();
        assertEquals(Status.UNAVAIL, mpt.getLinkStatus());
        mpt.stopAsync().awaitTerminated();
    }

    @Test
    public void testConnectionTimeout1() throws Exception {
        broker.connAckDelayMillis = 5000;
        broker.start();

        var mpt = getLink(false, null);
        mpt.startAsync().awaitRunning();
        Thread.sleep(2000);
        assertEquals(Status.UNAVAIL, mpt.getLinkStatus());
        mpt.stopAsync().awaitTerminated();
    }

    @Test
    public void testConnectionTimeout2() throws Exception {
        // does not apply because it does not subscribe to anything
        broker.subAckDelayMillis = 5000;
        broker.start();

        var mpt = getLink(false, null);
        mpt.startAsync().awaitRunning();
        Thread.sleep(1000);
        assertEquals(Status.OK, mpt.getLinkStatus());
        mpt.stopAsync().awaitTerminated();
    }

    @Test
    public void testConnectionNack() throws Exception {
        broker.sendNegativeConnAck = true;
        broker.start();

        var mpt = getLink(false, null);
        mpt.startAsync().awaitRunning();
        Thread.sleep(1000);
        assertEquals(Status.UNAVAIL, mpt.getLinkStatus());
        mpt.stopAsync().awaitTerminated();
    }

    @Test
    public void testSubNack() throws Exception {
        broker.sendNegativeSubAck = true;
        broker.start();

        var mpt = getLink(false, "tm");
        mpt.startAsync().awaitRunning();

        Thread.sleep(1000);
        assertEquals(Status.UNAVAIL, mpt.getLinkStatus());
        mpt.stopAsync().awaitTerminated();
    }

    @Test
    public void test1() throws Exception {
        broker.start();

        var mpt = getLink(true, "tm");
        mpt.startAsync().awaitRunning();

        Thread.sleep(1000);
        assertEquals(Status.OK, mpt.getLinkStatus());

        byte[] commandData = "testCommand".getBytes();

        var pc = mock(PreparedCommand.class);
        when(pc.getBinary()).thenReturn(commandData);

        mpt.sendCommand(pc);
        Thread.sleep(1000);

        assertEquals(1, broker.received.size());
        assertArrayEquals(commandData, broker.received.get(0));

        mpt.stopAsync().awaitTerminated();
    }

    /**
     * A broker restart: the connection drops, the broker comes back having forgotten the (clean)
     * session, and Paho reconnects on its own. The link has to subscribe again, or it sits there
     * connected, reporting OK and receiving nothing.
     */
    @Test
    public void testResubscribesAfterBrokerRestart() throws Exception {
        broker.start();

        var mpt = getLink(true, "tm");
        mpt.setTmSink(tmPacket -> {
        });
        mpt.startAsync().awaitRunning();

        waitFor("the first subscription", () -> broker.subscriptions.get() == 1, 5000);
        broker.publish("tm", new byte[32]);
        waitFor("a message before the restart", () -> mpt.getDataInCount() == 1, 5000);

        broker.shutdownNetwork();
        waitFor("the link to notice the broker is gone", () -> mpt.getLinkStatus() == Status.UNAVAIL, 5000);
        // Stay down past Paho's first reconnect attempt, as a real restart does.
        Thread.sleep(1500);
        broker.start();

        waitFor("a second subscription after the reconnect", () -> broker.subscriptions.get() == 2, 20000);
        waitFor("the link to be OK again", () -> mpt.getLinkStatus() == Status.OK, 5000);
        broker.publish("tm", new byte[32]);
        waitFor("a message after the restart", () -> mpt.getDataInCount() == 2, 5000);

        mpt.stopAsync().awaitTerminated();
    }

    /** The same when only the connection drops and the broker itself stays up. */
    @Test
    public void testResubscribesAfterConnectionDrop() throws Exception {
        broker.start();

        var mpt = getLink(true, "tm");
        mpt.setTmSink(tmPacket -> {
        });
        mpt.startAsync().awaitRunning();
        waitFor("the first subscription", () -> broker.subscriptions.get() == 1, 5000);

        broker.dropClients();

        waitFor("a second subscription after the reconnect", () -> broker.subscriptions.get() == 2, 20000);
        broker.publish("tm", new byte[32]);
        waitFor("a message after the reconnect", () -> mpt.getDataInCount() == 1, 5000);
        assertEquals(Status.OK, mpt.getLinkStatus());

        mpt.stopAsync().awaitTerminated();
    }

    /**
     * The broker refuses the first connection (down, or rejecting a burst of connects, as Kepler's
     * does). Paho never retries a failed first connect; the link must, or it stays down until an
     * operator cycles it.
     */
    @Test
    public void retriesAFailedFirstConnection() throws Exception {
        // Pick the port first so the link can be pointed at a broker that is not listening yet.
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            broker.port = s.getLocalPort();
        }
        var mpt = getLink(true, "tm");
        mpt.setTmSink(tmPacket -> {
        });
        mpt.startAsync().awaitRunning();
        Thread.sleep(1500);
        assertEquals(Status.UNAVAIL, mpt.getLinkStatus(), "nothing listening yet");

        broker.start();
        // retries at 1 s, 2 s, 4 s ... after the failures: well inside this wait
        waitFor("the link to connect on a retry", () -> broker.subscriptions.get() == 1, 15000);
        waitFor("the link to be OK", () -> mpt.getLinkStatus() == Status.OK, 5000);

        mpt.stopAsync().awaitTerminated();
    }

    /** Disabling the link stops the retries. */
    @Test
    public void disablingStopsTheRetries() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            broker.port = s.getLocalPort();
        }
        var mpt = getLink(true, "tm");
        mpt.startAsync().awaitRunning();
        Thread.sleep(1500);
        mpt.disable();
        broker.start();
        Thread.sleep(6000);
        assertEquals(0, broker.subscriptions.get(), "a disabled link must not connect");

        mpt.enable();
        waitFor("the link to connect once enabled", () -> broker.subscriptions.get() == 1, 10000);
        mpt.stopAsync().awaitTerminated();
    }

    /** Disabling and enabling the link by hand still leaves exactly one subscription per connect. */
    @Test
    public void testSubscribesOncePerConnect() throws Exception {
        broker.start();

        var mpt = getLink(true, "tm");
        mpt.startAsync().awaitRunning();
        waitFor("the first subscription", () -> broker.subscriptions.get() == 1, 5000);

        mpt.disable();
        waitFor("the link to disconnect", () -> broker.clients.isEmpty(), 5000);
        mpt.enable();
        waitFor("a subscription after enabling", () -> broker.subscriptions.get() == 2, 5000);

        Thread.sleep(1000);
        assertEquals(2, broker.subscriptions.get());

        mpt.stopAsync().awaitTerminated();
    }

    static void waitFor(String what, BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
    }

    MqttPacketLink getLink(boolean autoReconnect, String tmTopic) {
        YConfiguration config = getConfig(broker.port, autoReconnect, tmTopic);

        MqttPacketLink mpt = new MqttPacketLink();
        try {
            // through the link's spec, as Yamcs does, so that options left out get their defaults
            config = mpt.getSpec().validate(config);
        } catch (ValidationException e) {
            throw new IllegalArgumentException(e);
        }
        mpt.init("test", "test", config);
        return mpt;

    }

    YConfiguration getConfig(int port, boolean autoReconnect, String tmTopic) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "test");
        m.put("class", MqttPacketLink.class.getName());
        m.put("brokers", Arrays.asList("tcp://localhost:" + port));
        m.put("clientId", "test-clientid");
        m.put("connectionTimeoutSecs", 1);
        m.put("autoReconnect", autoReconnect);
        m.put("keepAliveSecs", 60);
        m.put("tcTopic", "tc");
        if (tmTopic != null) {
            m.put("tmTopic", tmTopic);
        }
        return YConfiguration.wrap(m);
    }

    static class FakeMqttBroker {
        List<byte[]> received = new ArrayList<>();
        final AtomicInteger subscriptions = new AtomicInteger();
        final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

        private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        private Channel serverChannel;
        private int port;

        long connAckDelayMillis = 0;
        long subAckDelayMillis = 0;
        long pubAckDelayMillis = 0;

        private boolean sendNegativeConnAck = false;
        private boolean sendNegativeSubAck = false;

        public void start() throws InterruptedException {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    // .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            clients.add(ch);
                            ch.pipeline().addLast(MqttEncoder.INSTANCE);
                            ch.pipeline().addLast(new MqttDecoder());
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<MqttMessage>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
                                    if (msg instanceof MqttConnectMessage) {
                                        ctx.executor().schedule(() -> {
                                            MqttConnAckMessage connAckMessage = MqttMessageBuilders.connAck()
                                                    .returnCode(sendNegativeConnAck
                                                            ? MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED
                                                            : MqttConnectReturnCode.CONNECTION_ACCEPTED)
                                                    .build();
                                            ctx.writeAndFlush(connAckMessage);
                                        }, connAckDelayMillis, TimeUnit.MILLISECONDS);
                                    } else if (msg instanceof MqttSubscribeMessage) {
                                        subscriptions.incrementAndGet();
                                        ctx.executor().schedule(() -> {
                                            MqttSubAckMessage subAckMessage = MqttMessageBuilders.subAck()
                                                    .packetId(((MqttSubscribeMessage) msg).variableHeader().messageId())
                                                    .addGrantedQos(sendNegativeSubAck ? MqttQoS.FAILURE
                                                            : MqttQoS.AT_MOST_ONCE)
                                                    .build();
                                            ctx.writeAndFlush(subAckMessage);

                                        }, subAckDelayMillis, TimeUnit.MILLISECONDS);
                                    } else if (msg instanceof MqttPublishMessage) {
                                        MqttPublishMessage msgp = (MqttPublishMessage) msg;
                                        received.add(ByteBufUtil.getBytes(msgp.payload()));
                                        ctx.executor().schedule(() -> {
                                            MqttMessage pubAckMessage = MqttMessageBuilders.pubAck()
                                                    .packetId(msgp.variableHeader().packetId())
                                                    .build();
                                            ctx.writeAndFlush(pubAckMessage);
                                        }, pubAckDelayMillis, TimeUnit.MILLISECONDS);
                                    }
                                }
                            });
                        }
                    });

            // port is 0 the first time (any free port) and stays the same across a restart
            serverChannel = b.bind(port).sync().channel();
            port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        /** Sends a message to every connected client, as a broker would to its subscribers. */
        public void publish(String topic, byte[] payload) {
            clients.writeAndFlush(MqttMessageBuilders.publish()
                    .topicName(topic)
                    .qos(MqttQoS.AT_MOST_ONCE)
                    .retained(false)
                    .payload(Unpooled.wrappedBuffer(payload))
                    .build());
        }

        /** Cuts every client connection; the broker keeps listening. */
        public void dropClients() throws InterruptedException {
            clients.close().sync();
        }

        /** Stops listening and cuts every connection; start() brings it back on the same port. */
        public void shutdownNetwork() throws InterruptedException {
            if (serverChannel != null) {
                serverChannel.close().sync();
                serverChannel = null;
            }
            dropClients();
        }

        public void stop() {
            if (serverChannel != null) {
                serverChannel.close();
            }
            clients.close();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }

        public void setConnAckDelayMillis(long connAckDelayMillis) {
            this.connAckDelayMillis = connAckDelayMillis;
        }

        public void setPubAckDelayMillis(long pubAckDelayMillis) {
            this.subAckDelayMillis = pubAckDelayMillis;
        }

        public void setSendNegativeConnAck(boolean sendNegativeConnAck) {
            this.sendNegativeConnAck = sendNegativeConnAck;
        }

        public void setSendNegativePubAck(boolean sendNegativePubAck) {
            this.sendNegativeSubAck = sendNegativePubAck;
        }
    }
}
