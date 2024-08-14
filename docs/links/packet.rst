TM/TC Packet Link
=================

This link subscribes to a MQTT topic for TM and sends TC to another MQTT topic. Both TC and TM are sent/received as packets.

Usage
-----

.. code-block:: yaml

    dataLinks:
       - name: tmtc        
         class: org.yamcs.mqtt.MqttPacketLink
         brokers:
            - tcp://test.mosquitto.org:1883 
         tmTopic: yamcs-tm
         tcTopic: yamcs-tc
         # other MQTT options
         # other link options


Options
-------
.. include:: _includes/mqtt-common-options.rst

tmTopic (string)
    The name of the topic to subscribe for TM packets. If it is not specified, no topic will be subscribed.
    Default: not specified

    
tmConverterClassName:
     The name of the class implementing :javadoc:`org.yamcs.mqtt.MqttToTmPacketConverter` that is used to extract the packet data from the MQTT message. By default (if not specified) the converter uses the MQTT message payload as the data and uses locally generated time as reception time.
     `org.yamcs.mqtt.LeafMqttToTmPacketConverter` can be used when connecting to LeafSpace ground station - in this case the messages received are json objects with two fields timestamp and payload.

tmConverterArgs
     The configuration that will be passed to the init method of the TM converter.

tcTopic (string)
    The name of the topic to which the TC packets are sent. If it is not specified, commanding will not be possible for this link.
    Default: not specified
    
tcConverterClassName:
     The name of the class implementing :javadoc:`org.yamcs.mqtt.FrameToMqttConverter` that is used to create the MQTT message from the commands. By default (if not specified) the converter sets the payload of the MQTT message as the binary command (after postprocessing).

tcConverterArgs
     The configuration that will be passed to the init method of the TC converter.
