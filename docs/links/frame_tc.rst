TC Frame Link
=============

This link sends out CCSDS TC frames as MQTT messages.

Usage
-----

.. code-block:: yaml

    dataLinks:
        class: org.yamcs.mqtt.MqttTcFrameLink
        # MQTT connection parameters
        brokers:
           - tcp://test.mosquitto.org:1883 
        topic: yamcs-tc-frames
        # other MQTT options
        # other frame link options

Options
-------

.. include:: _includes/mqtt-common-options.rst

topic (string)
    **Required** The name of the topic to which the TC frames are sent.
    Default: not specified

converterClassName:
     The name of the class implementing :javadoc:`org.yamcs.mqtt.FrameToMqttConverter` that is used to create the MQTT message from the binary frame data. By default (if not specified) the converter sets the payload of the MQTT message as the binary frame data.

converterArgs
     The configuration that will be passed to the init method of the converter.

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.
