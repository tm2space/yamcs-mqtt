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
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.Spec.OptionType;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.AbstractTcTmParamLink;
import org.yamcs.utils.StringConverter;

/**
 * MQTT packet link - supports TM and TC packets
 */
public class MqttPacketLink extends AbstractTcTmParamLink implements IMqttMessageListener {
    MqttConnectOptions connOpts;
    MqttAsyncClient client;
    String tmTopic, tcTopic;
    volatile Throwable subscriptionFailure;

    @Override
    public void init(String yamcsInstance, String name, YConfiguration config) throws ConfigurationException {
        super.init(yamcsInstance, name, config);
        connOpts = MqttUtils.getConnectionOptions(config);
        tmTopic = config.getString("tmTopic", null);
        tcTopic = config.getString("tcTopic", null);
        client = MqttUtils.newClient(config);
    }

    @Override
    public Spec getSpec() {
        var spec = getDefaultSpec();
        MqttUtils.addConnectionOptionsToSpec(spec);
        spec.addOption("tmTopic", OptionType.STRING).withRequired(false);
        spec.addOption("tcTopic", OptionType.STRING).withRequired(false);

        return spec;
    }

    @Override
    public boolean sendCommand(PreparedCommand preparedCommand) {
        log.debug("Sending command {}", preparedCommand);
        byte[] data = postprocess(preparedCommand);
        if (data == null) {
            return false;
        }
        try {
            client.publish(tcTopic, data, 0, false, null, new IMqttActionListener() {
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
        byte[] data = message.getPayload();
        dataIn(1, data.length);

        var tmPacket = new TmPacket(timeService.getMissionTime(), data);
        tmPacket = packetPreprocessor.process(tmPacket);
        if (tmPacket != null) {
            super.processPacket(tmPacket);
        }
    }

    @Override
    protected void doStart() {
        if (!isDisabled()) {
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
        return "";
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
