package org.yamcs.mqtt;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.NetworkModuleService;
import org.yamcs.ConfigurationException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducer;
import org.yamcs.logging.Log;
import org.yamcs.Spec.OptionType;

/**
 * A set of utilities used by the MQTT packet and frame links to avoid code duplication
 */
public class MqttUtils {
    
    static {
        try {
            // Register SSL network module for mqtts:// and ssl:// schemes
            NetworkModuleService.validateURI("ssl://test:8883");
        } catch (Exception e) {
            // SSL network module not available, continue without it
        }
    }

    /**
     * create a new MQTT async client with the clientId and initial broker loaded from the config object
     */
    static MqttAsyncClient newClient(YConfiguration config) throws ConfigurationException {
        try {
            List<String> brokers = config.getList("brokers");
            String clientId = config.getString("clientId", MqttClient.generateClientId());

            return new MqttAsyncClient(brokers.get(0), clientId);
        } catch (MqttException e) {
            throw new ConfigurationException(e);
        }
    }

    static MqttConnectOptions getConnectionOptions(YConfiguration config) {
        MqttConnectOptions connOpts = new MqttConnectOptions();

        connOpts.setAutomaticReconnect(config.getBoolean("autoReconnect"));
        List<String> brokers = config.getList("brokers");
        connOpts.setServerURIs(brokers.toArray(new String[0]));
        if (config.containsKey("username")) {
            connOpts.setUserName(config.getString("username"));
            connOpts.setPassword(config.getString("password").toCharArray());
        }
        connOpts.setConnectionTimeout(config.getInt("connectionTimeoutSecs"));
        connOpts.setKeepAliveInterval(config.getInt("keepAliveSecs"));
        // Pin the protocol version. With Paho's MQTT_VERSION_DEFAULT (0) the client tries 3.1.1
        // and, on ANY connect failure, ConnectActionListener rewrites the shared options to 3.1
        // and reconnects - so one transient failure silently downgrades the link for good.
        // Some brokers (Leaf Space loopback sandbox, 2026-09-14) accept 3.1 clients but never
        // route their messages, which shows up as PUBACKs with no downlink traffic.
        String mqttVersion = config.getString("mqttVersion");
        switch (mqttVersion) {
        case "3.1.1":
            connOpts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            break;
        case "3.1":
            connOpts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1);
            break;
        case "default":
            connOpts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_DEFAULT);
            break;
        default:
            throw new ConfigurationException("mqttVersion must be one of 3.1.1, 3.1, default; got " + mqttVersion);
        }
        connOpts.setCleanSession(true);

        // Enable SSL support for mqtts://, ssl:// and wss:// URLs.
        // wss:// must be handled here too: without an explicit SocketFactory, Paho's
        // WebSocketSecureNetworkModuleFactory builds its own SSLSocketFactoryFactory, which
        // reads the javax.net.ssl.trustStore system property (Yamcs points it at etc/trustStore)
        // and fails with FileNotFoundException when that file does not exist. The JVM default
        // SSLContext tolerates the missing file and falls back to the JRE cacerts.
        for (String broker : brokers) {
            if (broker.startsWith("ssl://") || broker.startsWith("mqtts://") || broker.startsWith("wss://")) {
                try {
                    // Use default SSL context for TLS connections
                    SSLContext sslContext = SSLContext.getDefault();
                    connOpts.setSocketFactory(sslContext.getSocketFactory());
                    break;
                } catch (Exception e) {
                    // Try with SSL properties as fallback
                    try {
                        Properties sslProps = new Properties();
                        sslProps.setProperty("com.ibm.ssl.protocol", "TLS");
                        connOpts.setSSLProperties(sslProps);
                        break;
                    } catch (Exception ex) {
                        // Continue without SSL
                    }
                }
            }
        }

        return connOpts;
    }

    static void addConnectionOptionsToSpec(Spec spec) {
        spec.addOption("brokers", OptionType.LIST).withElementType(OptionType.STRING).withRequired(true);
        spec.addOption("username", OptionType.STRING).withRequired(false);
        spec.addOption("password", OptionType.STRING).withRequired(false);
        spec.addOption("clientId", OptionType.STRING).withRequired(false);

        spec.addOption("connectionTimeoutSecs", OptionType.INTEGER).withDefault(5);
        spec.addOption("autoReconnect", OptionType.BOOLEAN).withDefault(true);
        spec.addOption("keepAliveSecs", OptionType.INTEGER).withDefault(60);
        spec.addOption("mqttVersion", OptionType.STRING).withDefault("3.1.1")
                .withDescription("MQTT protocol version: 3.1.1 (default), 3.1, or default (Paho auto-negotiate with 3.1 fallback)");
        spec.requireTogether("username", "password");
    }

    /** Told how a connection attempt ended. */
    interface ConnectListener {
        void connected();

        void failed(Throwable cause);
    }

    /**
     * Retries a connection that failed. Paho reconnects on its own only after a connection has
     * succeeded once; a link whose very first attempt is refused would otherwise stay down until
     * an operator disabled and enabled it. The delay doubles from one second to a minute between
     * attempts and resets once connected.
     */
    static final class ConnectRetry {
        private static final long MAX_DELAY_MS = 60_000;
        private final ScheduledExecutorService timer;
        private final Log log;
        private int attempt;
        private ScheduledFuture<?> pending;
        private boolean enabled = true;

        ConnectRetry(String name, Log log) {
            this.log = log;
            this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mqtt-connect-retry-" + name);
                t.setDaemon(true);
                return t;
            });
        }

        /** Schedules another attempt, unless retries were switched off (link disabled or stopped). */
        synchronized void afterFailure(Runnable connect) {
            if (!enabled) {
                return;
            }
            long delay = Math.min(MAX_DELAY_MS, 1000L << Math.min(attempt, 6));
            attempt++;
            log.warn("Retrying the MQTT connection in {} s (attempt {})", delay / 1000, attempt + 1);
            pending = timer.schedule(connect, delay, TimeUnit.MILLISECONDS);
        }

        synchronized void connected() {
            attempt = 0;
        }

        /** Enabling the link: retries allowed, counting from the start. */
        synchronized void reset() {
            cancel();
            enabled = true;
            attempt = 0;
        }

        /** Disabling or stopping the link: no further attempts. */
        synchronized void cancel() {
            enabled = false;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
        }

        synchronized void shutdown() {
            cancel();
            timer.shutdownNow();
        }
    }

    /** The exception and its causes, for a log line: Paho's own message is often just "MqttException". */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 400; t = t.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null && !t.getMessage().equals(t.getClass().getName())) {
                sb.append(": ").append(t.getMessage());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Connect to MQTT
     */
    static void connect(MqttConnectOptions connOpts, MqttAsyncClient client, Log log, EventProducer eventProducer)
            throws MqttException {
        connect(connOpts, client, log, eventProducer, null);
    }

    static void connect(MqttConnectOptions connOpts, MqttAsyncClient client, Log log, EventProducer eventProducer,
            ConnectListener listener)
            throws MqttException {
        log.info("Connecting to MQTT with clientId {} and options: {}", client.getClientId(), connOpts);

        client.connect(connOpts, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken token) {
                log.info("Succesfully connected to MQTT");
                if (listener != null) {
                    listener.connected();
                }
            }

            @Override
            public void onFailure(IMqttToken t, Throwable e) {
                String msg = "Failed to connect to MQTT with clientId " + client.getClientId() + ": " + describe(e);
                eventProducer.sendWarning(msg);
                log.warn("{}", msg);
                if (listener != null) {
                    listener.failed(e);
                }
            }
        });
    }

    /**
     * Connect MQTT and subscribe to a given topic.
     * <p>
     * The subscription is made every time a connection is established, not only the first time. The
     * links connect with a clean session, so a broker that restarts - or any dropped connection that
     * Paho's automatic reconnect restores - comes back knowing nothing of the subscription, and Paho
     * drops the message listener along with it. Subscribing only after the first connect left the link
     * connected, reporting OK and receiving nothing until Yamcs was restarted.
     */
    static void connectAndSubscribe(MqttConnectOptions connOpts, MqttAsyncClient client,
            IMqttMessageListener messageListener, String topic, Log log, EventProducer eventProducer,
            SubscriptionFailureCallback subscriptionFailureCallback)
            throws MqttException {
        connectAndSubscribe(connOpts, client, messageListener, topic, log, eventProducer, subscriptionFailureCallback,
                null);
    }

    static void connectAndSubscribe(MqttConnectOptions connOpts, MqttAsyncClient client,
            IMqttMessageListener messageListener, String topic, Log log, EventProducer eventProducer,
            SubscriptionFailureCallback subscriptionFailureCallback, ConnectListener listener)
            throws MqttException {
        log.info("Connecting to MQTT with clientId {} and options: {}", client.getClientId(), connOpts);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    try {
                        String msg = "Reconnected to MQTT at " + serverURI + "; subscribing again to " + topic;
                        log.info("{}", msg);
                        eventProducer.sendInfo(msg);
                    } catch (RuntimeException e) {
                        log.warn("Error reporting the MQTT reconnection", e);
                    }
                }
                subscribe(client, messageListener, topic, log, eventProducer, subscriptionFailureCallback);
            }

            @Override
            public void connectionLost(Throwable cause) {
                // Nothing may escape from here: Paho starts its automatic reconnect only after this
                // returns normally, and silently gives up on reconnecting if it throws.
                try {
                    String msg = "Lost the MQTT connection of clientId " + client.getClientId() + ": " + cause;
                    log.warn("{}", msg);
                    eventProducer.sendWarning(msg);
                } catch (RuntimeException e) {
                    log.warn("Error reporting the lost MQTT connection", e);
                }
            }

            @Override
            public void messageArrived(String t, MqttMessage message) throws Exception {
                // Paho hands a message here only when no subscription listener matches its topic.
                messageListener.messageArrived(t, message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        client.connect(connOpts, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken token) {
                // the subscription follows in connectComplete, which Paho calls right after this
                log.info("Succesfully connected to MQTT");
                if (listener != null) {
                    listener.connected();
                }
            }

            @Override
            public void onFailure(IMqttToken t, Throwable e) {
                String msg = "Failed to connect to MQTT with clientId " + client.getClientId() + ": " + describe(e);
                eventProducer.sendWarning(msg);
                log.warn("{}", msg);
                if (listener != null) {
                    listener.failed(e);
                }
            }
        });
    }

    private static void subscribe(MqttAsyncClient client, IMqttMessageListener messageListener, String topic,
            Log log, EventProducer eventProducer, SubscriptionFailureCallback subscriptionFailureCallback) {
        try {
            client.subscribe(topic, 2, messageListener).setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken t) {
                    int[] granted = t.getGrantedQos();
                    if (granted.length != 1 || granted[0] > 2) {
                        String msg = "Subscription to " + topic + " failed; granted QoS: "
                                + Arrays.toString(granted);
                        eventProducer.sendWarning(msg);
                        subscriptionFailureCallback.setSubscriptionFailure(new Exception(msg));
                    } else {
                        log.info("Succesfully subscribed to {}", topic);
                        // a failure from before a reconnect no longer holds
                        subscriptionFailureCallback.setSubscriptionFailure(null);
                    }
                }

                @Override
                public void onFailure(IMqttToken t, Throwable e) {
                    String msg = "Subscription to " + topic + " failed: " + e.getMessage();
                    eventProducer.sendWarning(msg);
                    log.warn("{}", msg);
                    subscriptionFailureCallback.setSubscriptionFailure(e);
                }

            });
        } catch (MqttException e) {
            log.warn("Subscription to {} failed: {}", topic, e.getMessage());
            subscriptionFailureCallback.setSubscriptionFailure(e);
        }
    }

    public static void doDisable(MqttAsyncClient client) throws MqttException {
        if (client.isConnected()) {
            client.disconnect();
        }
    }

    public static void doStop(MqttAsyncClient client, NotifyStoppedCallback stopCb, NotifyFailedCallback failCb) {
        try {
            if (client.isConnected()) {
                client.disconnect(null,
                        new IMqttActionListener() {
                            @Override
                            public void onSuccess(IMqttToken t) {
                                try {
                                    client.close();
                                    stopCb.notifyStopped();
                                } catch (MqttException e) {
                                    failCb.notifyFailed(e);
                                }
                            }

                            @Override
                            public void onFailure(IMqttToken t, Throwable e) {
                                failCb.notifyFailed(e);
                            }
                        });
            } else {
                client.disconnectForcibly(0, 0, false);
                client.close();
                stopCb.notifyStopped();
            }
        } catch (MqttException e) {
            failCb.notifyFailed(e);
        }
    }

    @FunctionalInterface
    public interface NotifyStoppedCallback {
        void notifyStopped();
    }

    @FunctionalInterface
    public interface NotifyFailedCallback {
        void notifyFailed(Throwable e);
    }

    @FunctionalInterface
    public interface SubscriptionFailureCallback {
        void setSubscriptionFailure(Throwable e);
    }
}
