/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class TestFlowStateEthernet {

    private static byte[] emptyPacket = new byte[] {
            // Ethernet header
            // Destination MAC address (6 octets): AC:CA:BA:00:15:01
            // Source MAC address (6 octets): AC:CA:BA:00:15:02
            // EtherType/Length (2 octets): 0x800 (IP)
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x01,
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x02,
            (byte) 0x08, (byte) 0x00,
            // IP header
            // 0               8               16              24             32
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |Version|  IDL  |    DSCP   |ECN|        Total Length           |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |        Identification         |Flags|      Fragment Offset    |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |      TTL      |    Protocol   |        Header Checksum        |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |                       Source IP address                       |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |                     Destination IP address                    |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            (byte) (((0x04) << 4) | 0x05), (byte) 0x00, (byte) 0x00, (byte) 0x1c,
            (byte) 0x00, (byte) 0x01, (byte) 0x5f, (byte) 0xff,
            (byte) 0x01, (byte) 0x11, (byte) 0x00, (byte) 0x00,
            (byte) 0xa9, (byte) 0xfe, (byte) 0x0f, (byte) 0x01, // 169.254.15.1
            (byte) 0xa9, (byte) 0xfe, (byte) 0x0f, (byte) 0x02, // 169.254.15.2
            // UDP header
            // 0               8               16              24             32
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |           Source Port         |        Destination Port       |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |             Length            |             Checksum          |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            (byte) 0x0b, (byte) 0x6d, (byte) 0x0b, (byte) 0x6d, // src/dst port
            (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x00  // length/chksum
    };

    private static byte[] fakePayload = new byte[] {
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
    };

    private static byte[] samplePacket = new byte[] {
            // Ethernet header
            // Destination MAC address (6 octets): AC:CA:BA:00:15:01
            // Source MAC address (6 octets): AC:CA:BA:00:15:02
            // EtherType/Length (2 octets): 0x800 (IP)
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x01,
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x02,
            (byte) 0x08, (byte) 0x00,
            // IP header
            // 0               8               16              24             32
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |Version|  IDL  |    DSCP   |ECN|        Total Length           |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |        Identification         |Flags|      Fragment Offset    |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |      TTL      |    Protocol   |        Header Checksum        |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |                       Source IP address                       |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |                     Destination IP address                    |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            (byte) (((0x04) << 4) | 0x05), (byte) 0x00, (byte) 0x00, (byte) 0x34,
            (byte) 0x00, (byte) 0x01, (byte) 0x5f, (byte) 0xff,
            (byte) 0x01, (byte) 0x11, (byte) 0x00, (byte) 0x00,
            (byte) 0xa9, (byte) 0xfe, (byte) 0x0f, (byte) 0x01, // 169.254.15.1
            (byte) 0xa9, (byte) 0xfe, (byte) 0x0f, (byte) 0x02, // 169.254.15.2
            // UDP header
            // 0               8               16              24             32
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |           Source Port         |        Destination Port       |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |             Length            |             Checksum          |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            (byte) 0x0b, (byte) 0x6d, (byte) 0x0b, (byte) 0x6d, // src/dst port
            (byte) 0x00, (byte) 0x20, (byte) 0x00, (byte) 0x00, // length/chksum
            // Fake payload
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
    };

    private static byte[] fakePayloadWithPads = new byte[] {
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            // Pads
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
    };

    @RunWith(Parameterized.class)
    public static class TestFlowStateEthernetValidPacket {
        private final byte[] data;
        private final FlowStateEthernet expected;

        final static private int EMPTY_FLOW_STATE_PAYLOAD_SIZE = 0;
        final static private int SAMPLE_FLOW_STATE_PAYLOAD_SIZE = 24;

        final static private byte IP_VERSION = (byte) 0x04;
        final static private byte IP_HEADER_LENGTH = (byte) 0x05;
        final static private byte DIFF_SERV = (byte) 0x00;
        final static private int FLOW_STATE_EMPTY_IP_LENGTH = 28;
        final static private short IDENTIFICATION = (short) 0x01;
        final static private byte FLAGS = (byte) 0b010;
        final static private short FRAGMENT_OFFSET = (short) 0b1111111111111;
        final static private byte TTL = (byte) 0x01;
        final static private byte UDP_PROTOCOL = (byte) 0x11;
        // final static private short IP_CHECKSUM = (short) 0x442e;
        final static private short IP_CHECKSUM = (short) 0x0;
        final static private IPv4Addr IP_SRC_ADDRESS =
                IPv4Addr.fromString("169.254.15.1");
        final static private IPv4Addr IP_DST_ADDRESS =
                IPv4Addr.fromString("169.254.15.2");
        final static private int UDP_PORT = 0x0b6d;
        final static private short UDP_CHECKSUM = (short) 0x0;
        final static private int FLOW_STATE_EMPTY_UDP_LENGTH = 8;
        // Borrowed from StackOverflow:
        //   http://stackoverflow.com/questions/9655181/
        final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

        public static String bytesToHex(byte[] bytes) {
            char[] hexChars = new char[bytes.length * 2];
            for (int j = 0; j < bytes.length; j++) {
                int v = bytes[j] & 0xFF;
                hexChars[j * 2] = hexArray[v >>> 4];
                hexChars[j * 2 + 1] = hexArray[v & 0x0F];
            }
            return new String(hexChars);
        }

        public TestFlowStateEthernetValidPacket(byte[] data,
                                                FlowStateEthernet expected) {
            this.data = data;
            this.expected = expected;
        }

        private static FlowStateEthernet makeFlowStateEthernetShell(
                int payloadSize) {
            FlowStateEthernet flowStateEthernet = new FlowStateEthernet();
            flowStateEthernet.setDestinationMACAddress(
                    new MAC(Arrays.copyOfRange(emptyPacket, 0, 6)));
            flowStateEthernet.setSourceMACAddress(
                    new MAC(Arrays.copyOfRange(emptyPacket, 6, 12)));
            flowStateEthernet.setEtherType((short) 0x800);

            IPv4 ipv4 = new IPv4();
            ipv4.setVersion(IP_VERSION);
            ipv4.setHeaderLength(IP_HEADER_LENGTH);
            ipv4.setDiffServ(DIFF_SERV);
            ipv4.setTotalLength(FLOW_STATE_EMPTY_IP_LENGTH + payloadSize);
            ipv4.setIdentification(IDENTIFICATION);
            ipv4.setFlags(FLAGS);
            ipv4.setFragmentOffset(FRAGMENT_OFFSET);
            ipv4.setTtl(TTL);
            ipv4.setProtocol(UDP_PROTOCOL);
            ipv4.setChecksum(IP_CHECKSUM);
            ipv4.setSourceAddress(IP_SRC_ADDRESS);
            ipv4.setDestinationAddress(IP_DST_ADDRESS);
            ipv4.setParent(flowStateEthernet);

            UDP udp = new UDP();
            udp.setSourcePort(UDP_PORT);
            udp.setDestinationPort(UDP_PORT);
            udp.setLength(FLOW_STATE_EMPTY_UDP_LENGTH + payloadSize);
            udp.setChecksum(UDP_CHECKSUM);
            udp.setParent(ipv4);
            ipv4.setPayload(udp);

            flowStateEthernet.setPayload(ipv4);

            return flowStateEthernet;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {
            FlowStateEthernet emptyFlowStateEthernet =
                    makeFlowStateEthernetShell(EMPTY_FLOW_STATE_PAYLOAD_SIZE);
            ElasticData emptyData = new ElasticData();
            emptyData.setData(new byte[]{});
            emptyFlowStateEthernet.setCore(emptyData);
            emptyFlowStateEthernet.setElasticDataLength(
                    EMPTY_FLOW_STATE_PAYLOAD_SIZE);

            FlowStateEthernet sampleFlowStateEthernet =
                    makeFlowStateEthernetShell(SAMPLE_FLOW_STATE_PAYLOAD_SIZE);
            ElasticData samplePayload = new ElasticData();
            samplePayload.setData(fakePayload);
            sampleFlowStateEthernet.setCore(samplePayload);
            sampleFlowStateEthernet.setElasticDataLength(
                    SAMPLE_FLOW_STATE_PAYLOAD_SIZE);

            FlowStateEthernet paddedFlowStateEthernet =
                    makeFlowStateEthernetShell(SAMPLE_FLOW_STATE_PAYLOAD_SIZE);
            ElasticData paddedPayload = new ElasticData();
            paddedPayload.setData(fakePayloadWithPads);
            paddedFlowStateEthernet.setCore(paddedPayload);
            paddedFlowStateEthernet.setElasticDataLength(
                    SAMPLE_FLOW_STATE_PAYLOAD_SIZE);

            byte[] empty = Arrays.copyOf(emptyPacket, emptyPacket.length);
            byte[] sample = Arrays.copyOf(samplePacket, samplePacket.length);

            return Arrays.asList(new Object[][]{
                    {empty, emptyFlowStateEthernet},
                    {sample, sampleFlowStateEthernet},
                    {sample, paddedFlowStateEthernet}
            });
        }

        @Test
        public void TestSerialize() throws Exception {
            int length = FlowStateEthernet.FLOW_STATE_ETHERNET_OVERHEAD +
                    this.expected.getElasticDataLength();
            byte[] serialized = new byte[length];
            ByteBuffer buff = ByteBuffer.wrap(serialized);
            int packetLength = expected.serialize(buff);

            Assert.assertEquals(packetLength, length);
            Assert.assertEquals(serialized.length, this.data.length);
            Assert.assertEquals(bytesToHex(this.expected.serialize()),
                    bytesToHex(this.data));
        }
    }
}
