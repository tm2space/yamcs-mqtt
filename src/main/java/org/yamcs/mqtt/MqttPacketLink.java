package org.yamcs.mqtt;

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
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.YObjectLoader;

/**
 * MQTT packet link - supports TM and TC packets
 */
public class MqttPacketLink extends AbstractTcTmParamLink implements IMqttMessageListener {
    MqttConnectOptions connOpts;
    MqttAsyncClient client;
    String tmTopic, tcTopic;
    volatile Throwable subscriptionFailure;
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
        connOpts = MqttUtils.getConnectionOptions(config);
        tmTopic = config.getString("tmTopic", null);
        tcTopic = config.getString("tcTopic", null);
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
        var msg = tcConverter.convert(preparedCommand);
        try {
            client.publish(tcTopic, msg, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    ackCommand(preparedCommand.getCommandId());
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    log.warn("Failed to send command {}", exception);
                    failedCommand(preparedCommand.getCommandId(), exception.toString());
                }
            });
            dataOut(1, data.length);
            return true;
        } catch (MqttException e) {
            log.warn("Failed to send command {}", e);
            return false;
        }
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
        MqttUtils.doDisable(client);
    }

    @Override
    protected void doEnable() throws Exception {
        doConnect();
    }

    private void doConnect() throws MqttException {
        subscriptionFailure = null;
        if (tmTopic != null) {
            MqttUtils.connectAndSubscribe(connOpts, client, this, tmTopic, log, eventProducer,
                    e -> subscriptionFailure = e);
        } else {
            MqttUtils.connect(connOpts, client, log, eventProducer);
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

}
