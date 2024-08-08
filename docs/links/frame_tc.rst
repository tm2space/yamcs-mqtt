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

topic (string(
    **Required** The name of the topic to which the TC frames are sent.
    Default: not specified



.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.
