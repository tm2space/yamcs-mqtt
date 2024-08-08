TM Frame Link
=============

This link interprets the MQTT messages as CCSDS Frames.

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

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.
