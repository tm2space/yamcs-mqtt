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
    
tcTopic (string)
    The name of the topic to which the TC packets are sent. If it is not specified, commanding will not be possible for this link.
    Default: not specified
    
