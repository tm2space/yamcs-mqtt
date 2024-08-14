# MQTT-Based Links in Yamcs

This project provides an implementation of MQTT-based links in Yamcs, supporting both packets and CCSDS frames.

## Documentation

https://docs.yamcs.org/yamcs-mqtt/

## Testing

This repository can be used for a quick test - it conatains a Yamcs with two instnaces (one using packets the other using frames) and a simulator playing and accepting CCSDS packets and frames. The simulator sends packets into raw format (the mqtt payload is the packet) and the frames in json format as used by the LeafSpace ground stations.


### Prerequisites

- Python 3
- Paho MQTT Client: On Ubuntu, you can install it with:
  ```bash
  sudo apt install python3-paho-mqtt
- Java 17+ and Maven


### Running

1. **Start the Simulator**:
   ```bash
   python simulator.py
2. **Start Yamcs**:
   ```bash
   mvn yamcs:run

You can navigate now to http://localhost:8090/ and see two Yamcs instances receiving data. Commands can be sent to both instances and they should be printed in the console where the simulator has been started.

### Configuration

This setup uses the public MQTT broker `test.mosquitto.org`. The following topics are used:

- `yamcs-tm-packets`
- `yamcs-tc-packets`
- `yamcs-tm-frames`
- `yamcs-tc-frames`

> **Note:** If someone else runs this example simultaneously, you might receive duplicate data. To avoid this, either use your own MQTT broker or change the topic names in the following files:
> - `src/main/yamcs/etc/yamcs.mqtt-packets.yaml`
> - `src/main/yamcs/etc/yamcs.mqtt-frames.yaml`
> - `simulator.py`
