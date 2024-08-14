TM Frame Link
=============

This link receives MQTT messages and processes them as CCSDS Frames.

Usage
-----


.. code-block:: yaml

    dataLinks:
        class: org.yamcs.mqtt.MqttTmFrameLink
        # MQTT connection parameters
        brokers:
           - tcp://test.mosquitto.org:1883 
        topic: yamcs-tc-frames
        # other MQTT options
        # other frame link options
        

.. include:: _includes/mqtt-common-options.rst

topic (string)
    **Required** The name of the topic to subscribe for TM frames.

converterClassName:
     The name of the class implementing :javadoc:`org.yamcs.mqtt.MqttToFrameConverter` that is used to extract the frame data from the MQTT message. By default (if not specified) the converter uses the MQTT message payload as the data and uses locally generated time as Earth Reception Time (ert).
     `org.yamcs.mqtt.LeafMqttToFrameConverter` can be used when connecting to LeafSpace ground station - in this case the messages received are json objects with two fields timestamp and payload.

converterArgs
     The configuration that will be passed to the init method of the converter.

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.
