package org.yamcs.mqtt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.yamcs.ConfigurationException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.Spec.OptionType;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.AbstractTcTmParamLink;
import org.yamcs.tctm.LinkAction;
import org.yamcs.tctm.LinkActionProvider;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.YObjectLoader;

/**
 * MQTT packet link - supports TM and TC packets
 */
public class MqttPacketLink extends AbstractTcTmParamLink implements IMqttMessageListener {
    MqttConnectOptions connOpts;
    MqttAsyncClient client;
    String tmTopic, tcTopic;
    // >0: publish the (post-processed) TC binary as separate MQTT messages of this many bytes each,
    // i.e. frame-by-frame uplink (mirrors the PlutoSDR per-frame TX). 0 = one publish of the whole
    // binary (default; unchanged behaviour for every other MQTT link).
    int tcFrameSize;
    // TC messages waiting for room in paho's in-flight window, oldest first (see publishQueued)
    private final ArrayDeque<TcMessage> tcQueue = new ArrayDeque<>();
    // TC publishes not yet acknowledged; guarded by tcQueue
    private int tcInFlight;
    volatile Throwable subscriptionFailure;
    MqttUtils.ConnectRetry retry;
    MqttToTmPacketConverter tmConverter;
    PreparedCommandToMqttConverter tcConverter;
    // Captured at init so the link's "Detailed status" (web UI) shows what the MQTT link is
    // configured with - broker, protocol version, topics, converter and the TM/TC processing
    // chain - the same way the PlutoSDR S-band/UHF links surface their RF/framing config.
    private String brokerUri, mqttVersion, tmConverterName, tcConverterName;
    private String packetPreprocessorName, commandPostprocessorName, cryptoMode;

    @Override
    public void init(String yamcsInstance, String linkName, YConfiguration config) throws ConfigurationException {
        super.init(yamcsInstance, linkName, config);
        // A postprocessor has no link page of its own: surface its actions (e.g. the UHF framing
        // postprocessor's "Configure TC framing") on this link.
        if (cmdPostProcessor instanceof LinkActionProvider) {
            for (LinkAction action : ((LinkActionProvider) cmdPostProcessor).getActions()) {
                addAction(action);
            }
        }
        connOpts = MqttUtils.getConnectionOptions(config);
        retry = new MqttUtils.ConnectRetry(linkName, log);
        tmTopic = config.getString("tmTopic", null);
        tcTopic = config.getString("tcTopic", null);
        tcFrameSize = config.getInt("tcFrameSize", 0);
        client = MqttUtils.newClient(config);

        tmConverter = YObjectLoader.loadObject(config.getString("tmConverterClassName"));
        tmConverter.init(yamcsInstance, linkName, config.getConfigOrEmpty("tmConverterArgs"));

        tcConverter = YObjectLoader.loadObject(config.getString("tcConverterClassName"));
        tcConverter.init(yamcsInstance, linkName, config.getConfigOrEmpty("tcConverterArgs"));

        java.util.List<String> brokers = config.getList("brokers");
        brokerUri = brokers.isEmpty() ? "?" : brokers.get(0);
        mqttVersion = config.getString("mqttVersion", "3.1.1");
        tmConverterName = simpleName(config.getString("tmConverterClassName", null));
        tcConverterName = simpleName(config.getString("tcConverterClassName", null));
        packetPreprocessorName = simpleName(config.getString("packetPreprocessorClassName", null));
        commandPostprocessorName = simpleName(config.getString("commandPostprocessorClassName", null));
        // Crypto mode lives in the pre/postprocessor args (or their inner processor for framed links).
        cryptoMode = firstNonNull(
                nestedString(config, "commandPostprocessorArgs", "mode"),
                nestedString(config, "commandPostprocessorArgs", "innerPostprocessorArgs", "mode"),
                nestedString(config, "packetPreprocessorArgs", "mode"));
    }

    private static String simpleName(String className) {
        if (className == null) {
            return null;
        }
        int i = className.lastIndexOf('.');
        return i >= 0 ? className.substring(i + 1) : className;
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Read a string at a nested config path, tolerating missing keys and env-substituted values. */
    private static String nestedString(YConfiguration config, String... path) {
        YConfiguration c = config;
        for (int i = 0; i < path.length - 1; i++) {
            if (c == null || !c.containsKey(path[i])) {
                return null;
            }
            c = c.getConfigOrEmpty(path[i]);
        }
        String leaf = path[path.length - 1];
        return (c != null && c.containsKey(leaf)) ? String.valueOf(c.get(leaf)) : null;
    }

    @Override
    public Spec getSpec() {
        var spec = getDefaultSpec();
        MqttUtils.addConnectionOptionsToSpec(spec);
        spec.addOption("tmTopic", OptionType.STRING).withRequired(false);
        spec.addOption("tcTopic", OptionType.STRING).withRequired(false);
        spec.addOption("tmConverterClassName", OptionType.STRING)
                .withDefault(DefaultMqttToTmPacketConverter.class.getName());
        spec.addOption("tmConverterArgs", OptionType.MAP).withRequired(false);

        spec.addOption("tcConverterClassName", OptionType.STRING)
                .withDefault(DefaultPreparedCommandToMqttConverter.class.getName());
        spec.addOption("tcConverterArgs", OptionType.MAP).withRequired(false);
        spec.addOption("tcFrameSize", OptionType.INTEGER).withDefault(0);

        return spec;
    }

    @Override
    public boolean sendCommand(PreparedCommand preparedCommand) {
        log.debug("Sending command {}", preparedCommand);
        byte[] data = postprocess(preparedCommand);
        if (data == null) {
            return false;
        }
        preparedCommand.setBinary(data);
        TcUplink uplink = new TcUplink(preparedCommand);
        List<MqttMessage> msgs = new ArrayList<>();
        if (tcFrameSize > 0 && data.length > tcFrameSize) {
            // Frame-by-frame uplink: each tcFrameSize-byte frame is its own MQTT message. Mirrors the
            // PlutoSDR per-frame TX.
            for (int off = 0; off < data.length; off += tcFrameSize) {
                msgs.add(new MqttMessage(Arrays.copyOfRange(data, off, Math.min(off + tcFrameSize, data.length))));
            }
        } else {
            msgs.add(tcConverter.convert(preparedCommand));
        }
        synchronized (tcQueue) {
            for (int idx = 0; idx < msgs.size(); idx++) {
                tcQueue.addLast(new TcMessage(uplink, msgs.get(idx), idx == msgs.size() - 1));
            }
        }
        publishQueued();
        dataOut(1, data.length);
        return true;
    }

    /**
     * Publishes queued TC messages, oldest first, while paho's in-flight window has room. Paho refuses
     * a publish once maxInflight are unacknowledged ("Too many publishes in progress"), so the rest
     * wait here and each PUBACK lets the next one out. Every TC goes through this one queue: a small
     * command sent while a large one is still going out waits its turn instead of landing between
     * the large one's frames.
     */
    private void publishQueued() {
        synchronized (tcQueue) {
            while (!tcQueue.isEmpty() && tcInFlight < connOpts.getMaxInflight()) {
                TcMessage tm = tcQueue.peekFirst();
                try {
                    client.publish(tcTopic, tm.msg, null, new IMqttActionListener() {
                        @Override
                        public void onSuccess(IMqttToken asyncActionToken) {
                            synchronized (tcQueue) {
                                tcInFlight--;
                                if (tm.last && !tm.uplink.failed) {
                                    ackCommand(tm.uplink.pc.getCommandId());
                                }
                            }
                            publishQueued();
                        }

                        @Override
                        public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                            log.warn("Failed to send command {}", exception);
                            synchronized (tcQueue) {
                                tcInFlight--;
                                failUplink(tm.uplink, exception.toString());
                            }
                            publishQueued();
                        }
                    });
                } catch (MqttException e) {
                    if (e.getReasonCode() == MqttException.REASON_CODE_MAX_INFLIGHT && tcInFlight > 0) {
                        // paho still counts a publish whose PUBACK is on its way to us: that callback
                        // calls back in here
                        return;
                    }
                    log.warn("Failed to send command {}", e);
                    failUplink(tm.uplink, e.toString());
                    continue;
                }
                tcQueue.pollFirst();
                tcInFlight++;
            }
        }
    }

    /**
     * Fails the command once and drops its messages still queued, so a command that lost a frame
     * never sends the rest of its frames. Called with the tcQueue lock held.
     */
    private void failUplink(TcUplink uplink, String reason) {
        if (uplink.failed) {
            return;
        }
        uplink.failed = true;
        tcQueue.removeIf(tm -> tm.uplink == uplink);
        failedCommand(uplink.pc.getCommandId(), reason);
    }

    /**
     * Called by the MQTT client when a message is received
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (log.isTraceEnabled()) {
            log.trace("topic: {}, got message: {}", topic,
                    StringConverter.arrayToHexString(message.getPayload(), true));
        }
        dataIn(1, message.getPayload().length);

        for(var tmPacket: tmConverter.convert(message)) {
            tmPacket = packetPreprocessor.process(tmPacket);
            if (tmPacket != null) {
                super.processPacket(tmPacket);
            }
        }
    }

    @Override
    protected void doStart() {
        if (isDisabled()) {
            notifyStarted();
        } else {
            try {
                doConnect();
                notifyStarted();
            } catch (MqttException e) {
                notifyFailed(e);
            }
        }
    }

    @Override
    protected void doStop() {
        retry.shutdown();
        MqttUtils.doStop(client, this::notifyStopped, this::notifyFailed);
    }

    @Override
    public String getDetailedStatus() {
        boolean connected = client != null && client.isConnected();
        String conn = connected ? "CONNECTED" : "DISCONNECTED";
        // getCurrentServerURI() throws NPE before the client has connected (no network modules yet),
        // so only ask paho once connected; otherwise show the configured broker.
        String server = brokerUri;
        if (connected) {
            try {
                String cur = client.getCurrentServerURI();
                if (cur != null) {
                    server = cur;
                }
            } catch (RuntimeException e) {
                // keep configured brokerUri
            }
        }
        String clientId = "?";
        if (client != null) {
            try {
                clientId = client.getClientId();
            } catch (RuntimeException e) {
                // ignore
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MQTT: [").append(conn).append("] ").append(server)
                .append(" (v").append(mqttVersion).append(", clientId ").append(clientId).append(")");
        sb.append(" | TM topic: ").append(tmTopic != null ? tmTopic : "-");
        sb.append(" | TC topic: ").append(tcTopic != null ? tcTopic : "-");
        if (tcFrameSize > 0) {
            sb.append(" | TC frame-split: ").append(tcFrameSize).append("B");
        }
        if (subscriptionFailure != null) {
            sb.append(" | SUBSCRIBE FAILED: ").append(subscriptionFailure.getMessage());
        }
        if (tmConverterName != null) {
            sb.append(" | TM converter: ").append(tmConverterName);
        }
        if (packetPreprocessorName != null) {
            sb.append(" | TM preproc: ").append(packetPreprocessorName);
        }
        if (commandPostprocessorName != null) {
            sb.append(" | TC postproc: ").append(commandPostprocessorName);
        }
        if (cryptoMode != null) {
            sb.append(" | crypto: ").append(cryptoMode);
        }
        return sb.toString();
    }

    @Override
    protected void doDisable() throws Exception {
        retry.cancel();
        MqttUtils.doDisable(client);
    }

    @Override
    protected void doEnable() throws Exception {
        retry.reset();
        doConnect();
    }

    private void doConnect() throws MqttException {
        subscriptionFailure = null;
        MqttUtils.ConnectListener onResult = new MqttUtils.ConnectListener() {
            @Override
            public void connected() {
                retry.connected();
            }

            @Override
            public void failed(Throwable cause) {
                retry.afterFailure(MqttPacketLink.this::reconnect);
            }
        };
        if (tmTopic != null) {
            MqttUtils.connectAndSubscribe(connOpts, client, this, tmTopic, log, eventProducer,
                    e -> subscriptionFailure = e, onResult);
        } else {
            MqttUtils.connect(connOpts, client, log, eventProducer, onResult);
        }
    }

    /** A retried first connection; runs on the retry timer. */
    private void reconnect() {
        if (isDisabled() || !isRunning() || client.isConnected()) {
            return;
        }
        try {
            doConnect();
        } catch (MqttException e) {
            log.warn("MQTT connection attempt could not be started: {}", MqttUtils.describe(e));
            retry.afterFailure(this::reconnect);
        }
    }

    @Override
    protected Status connectionStatus() {
        if (client.isConnected() && subscriptionFailure == null) {
            return Status.OK;
        } else {
            return Status.UNAVAIL;
        }
    }

    @Override
    public boolean isTcDataLinkImplemented() {
        return tcTopic != null;
    }

    @Override
    public boolean isTmPacketDataLinkImplemented() {
        return tmTopic != null;
    }

    /** One command on its way out; failed is guarded by tcQueue. */
    private static final class TcUplink {
        final PreparedCommand pc;
        boolean failed;

        TcUplink(PreparedCommand pc) {
            this.pc = pc;
        }
    }

    /** One MQTT message of a command; the PUBACK of the last one acks the command. */
    private static final class TcMessage {
        final TcUplink uplink;
        final MqttMessage msg;
        final boolean last;

        TcMessage(TcUplink uplink, MqttMessage msg, boolean last) {
            this.uplink = uplink;
            this.msg = msg;
            this.last = last;
        }
    }
}
