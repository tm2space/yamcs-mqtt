import binascii
import io
import sys
import struct
from threading import Thread
from time import sleep
import paho.mqtt.client as mqtt
import argparse
import json
import datetime

AOS_FRAME_LENGTH = 1115 
SPACECRAFT_ID = 29
VCID = 1
IDLE_APID = 0x7FF

def make_idle_ccsds_packet(length):
    if length < 7:
        raise ValueError("Length must be at least 7 bytes.")

    idle_packet = bytearray(length)
    version_number = 0b000  # 3 bits
    packet_type = 0b0  # 1 bit (Telemetry)
    secondary_header_flag = 0b0  # 1 bit (No secondary header)
    sequence_flags = 0b11  # 2 bits (Standalone packet)
    sequence_count = 0b00000000000000  # 14 bits (Usually set to 0 for idle packets)
    data_length = length - 7  # 16 bits (Remaining length minus primary header)

    struct.pack_into('>H', idle_packet, 0, (version_number << 13) | (packet_type << 12) | 
                                        (secondary_header_flag << 11) | IDLE_APID)
    struct.pack_into('>H', idle_packet, 2, (sequence_flags << 14) | sequence_count)
    struct.pack_into('>H', idle_packet, 4, data_length)

    idle_packet[6:] = bytes(length - 6) 
    return idle_packet

def build_aos_frame(packet, seq_count):
    """
    Creates an AOS frame from the given packet followed by an idle packet.
    
    Args:
        packet (bytes): The CCSDS packet to embed into the AOS frame.
    
    Returns:
        bytes: The constructed AOS frame, or None if the packet is too large.
    """
    if len(packet) + 15 > AOS_FRAME_LENGTH:
        print("Packet {} too large - cannot fit it in a frame together with an idle packet".format(len(packet)))
        return None
    
    #make a AOS frame from a CCSDS packet followed by an idle packet
    aos_frame = bytearray(AOS_FRAME_LENGTH)

    # Primary header: Version (2 bits) + SCID (8 bits) and VCID (6 bits)
    w = (1 << 14) | (SPACECRAFT_ID << 6) | VCID
    struct.pack_into('>H', aos_frame, 0, w)  
            
    # Frame sequence number (24 bits)
    struct.pack_into('>B', aos_frame, 2, (seq_count >> 16) & 0xFF)  # Most significant byte
    struct.pack_into('>H', aos_frame, 3, seq_count & 0xFFFF)  # Remaining two bytes
          
    # signalign field
    struct.pack_into('>B', aos_frame, 5, 0)  

    # M_PDU header
    struct.pack_into('>H', aos_frame, 6, 0)  

    offset = 8
    # Populate the frame with the packet
    aos_frame[offset:offset + len(packet)] = packet
    offset+=len(packet)
     
    idle_packet = make_idle_ccsds_packet(AOS_FRAME_LENGTH - offset)
    aos_frame[offset:] = idle_packet
    return aos_frame
    


def send_tm(simulator):
    with io.open('testdata.ccsds', 'rb') as f:        
        header = bytearray(6)
        while f.readinto(header) == 6:
            (pkt_len,) = struct.unpack_from('>H', header, 4)

            packet = bytearray(pkt_len + 7)
            f.seek(-6, io.SEEK_CUR)
            f.readinto(packet)

            simulator.client.publish(simulator.tm_packet_topic, packet)
            simulator.tm_packet_counter += 1
           
            aos_frame = build_aos_frame(packet, simulator.tm_frame_counter)
            if (aos_frame):
                #send the frame in json Leaf format
                payload_str = " ".join(f"0x{byte:02x}" for byte in aos_frame)
                data = {
                    "timestamp": datetime.datetime.now().isoformat(),
                    "payload": payload_str
                }
                json_data = json.dumps(data)
                print(f"Sending data {json_data}");
                simulator.client.publish(simulator.tm_frame_topic, json_data)
                simulator.tm_frame_counter += 1
            
            
            sleep(1)


def on_tc_packet(client, userdata, message):
    simulator = userdata
    simulator.last_tc = message.payload
    simulator.tc_packet_counter += 1


def on_tc_frame(client, userdata, message):
    simulator = userdata
    simulator.last_tc = message.payload
    simulator.tc_frame_counter += 1


class Simulator():

    def __init__(self, broker):
        self.tm_packet_counter = 0
        self.tc_packet_counter = 0
        self.tm_frame_counter = 0
        self.tc_frame_counter = 0
        self.tm_thread = None
        self.last_tc = None
        self.tm_packet_topic = "yamcs-tm-packets"
        self.tc_packet_topic = "yamcs-tc-packets"
        self.tm_frame_topic = "yamcs-tm-frames"
        self.tc_frame_topic = "yamcs-tc-frames"
        self.client = mqtt.Client(userdata=self)
        
        if broker.startswith("tcp://"):
            broker = broker[6:]
            use_tls = False
        elif broker.startswith("ssl://") or broker.startswith("tls://"):
            broker = broker[6:]
            use_tls = True
        else:
            raise ValueError("Broker must start with tcp://, ssl://, or tls://")
       
        self.broker, self.port = broker.split(':')
        self.port = int(self.port)
        
        if use_tls:
            self.client.tls_set(cert_reqs=ssl.CERT_NONE)
            self.client.tls_insecure_set(True)
        
        print(f"Connecting to broker: {self.broker} on port: {self.port} (TLS: {use_tls})")             
        self.client.connect(self.broker, self.port)
        self.client.subscribe(self.tc_packet_topic)
        self.client.message_callback_add(self.tc_packet_topic, on_tc_packet)
        
        self.client.subscribe(self.tc_frame_topic)
        self.client.message_callback_add(self.tc_frame_topic, on_tc_frame)


    def start(self):
        self.tm_thread = Thread(target=send_tm, args=(self,))
        self.tm_thread.daemon = True
        self.tm_thread.start()
        self.client.loop_start()

    def print_status(self):
        cmdhex = None
        if self.last_tc:
            cmdhex = binascii.hexlify(self.last_tc).decode('ascii')
        return 'Sent: {} TM packets and {} TM frames. Received: {} TC packets and {} TC frames. Last TC: {}'.format(
            self.tm_packet_counter, self.tm_frame_counter, self.tc_packet_counter, self.tc_frame_counter, cmdhex)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='MQTT Simulator')
    parser.add_argument('--broker', type=str, default='tcp://test.mosquitto.org:1883', help='MQTT broker address')
   
    args = parser.parse_args()

    simulator = Simulator(args.broker)
    simulator.start()

    try:
        prev_status = None
        while True:
            status = simulator.print_status()
            if status != prev_status:
                sys.stdout.write('\r')
                sys.stdout.write(status)
                sys.stdout.flush()
                prev_status = status
            sleep(0.5)
    except KeyboardInterrupt:
        sys.stdout.write('\n')
        sys.stdout.flush()
        simulator.client.loop_stop()
