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
            // Destination MAC address (6 octets): AC:CA:BA:00:15:02
            // Source MAC address (6 octets): AC:CA:BA:00:15:01
            // EtherType/Length (2 octets): 0x800 (IP)
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x02,
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x01,
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
            (byte) 0x00, (byte) 0x01, (byte) 0x40, (byte) 0x00,
            (byte) 0x40, (byte) 0x11, (byte) 0x00, (byte) 0x00,
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
            (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x00 // length/chksum
    };

    private static byte[] fakePayload = new byte[] {
            (byte) 0x18, (byte) 0x08, (byte) 0x01, (byte) 0x12,
            (byte) 0x12, (byte) 0x09, (byte) 0x30, (byte) 0x4d,
            (byte) 0x83, (byte) 0x8b, (byte) 0x04, (byte) 0x18,
            (byte) 0xa4, (byte) 0x24, (byte) 0x11, (byte) 0x51,
            (byte) 0xb5, (byte) 0xeb, (byte) 0x27, (byte) 0x3b,
            (byte) 0x18, (byte) 0x5d, (byte) 0xb0, (byte) 0x18
    };

    private static byte[] samplePacket = new byte[] {
            // Ethernet header
            // Destination MAC address (6 octets): AC:CA:BA:00:15:02
            // Source MAC address (6 octets): AC:CA:BA:00:15:01
            // EtherType/Length (2 octets): 0x800 (IP)
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x02,
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x01,
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
            (byte) 0x00, (byte) 0x01, (byte) 0x40, (byte) 0x00,
            (byte) 0x40, (byte) 0x11, (byte) 0x00, (byte) 0x00,
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
            (byte) 0x18, (byte) 0x08, (byte) 0x01, (byte) 0x12,
            (byte) 0x12, (byte) 0x09, (byte) 0x30, (byte) 0x4d,
            (byte) 0x83, (byte) 0x8b, (byte) 0x04, (byte) 0x18,
            (byte) 0xa4, (byte) 0x24, (byte) 0x11, (byte) 0x51,
            (byte) 0xb5, (byte) 0xeb, (byte) 0x27, (byte) 0x3b,
            (byte) 0x18, (byte) 0x5d, (byte) 0xb0, (byte) 0x18
    };

    private static byte[] fakePayloadWithPads = new byte[] {
            (byte) 0x18, (byte) 0x08, (byte) 0x01, (byte) 0x12,
            (byte) 0x12, (byte) 0x09, (byte) 0x30, (byte) 0x4d,
            (byte) 0x83, (byte) 0x8b, (byte) 0x04, (byte) 0x18,
            (byte) 0xa4, (byte) 0x24, (byte) 0x11, (byte) 0x51,
            (byte) 0xb5, (byte) 0xeb, (byte) 0x27, (byte) 0x3b,
            (byte) 0x18, (byte) 0x5d, (byte) 0xb0, (byte) 0x18,
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

        final static private int SAMPLE_FLOW_STATE_PAYLOAD_SIZE = 24;

        public TestFlowStateEthernetValidPacket(byte[] data,
                                                FlowStateEthernet expected) {
            this.data = data;
            this.expected = expected;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {
            FlowStateEthernet emptyFlowStateEthernet =
                new FlowStateEthernet(new byte[0]);

            FlowStateEthernet sampleFlowStateEthernet =
                new FlowStateEthernet(fakePayload);

            FlowStateEthernet paddedFlowStateEthernet =
                new FlowStateEthernet(fakePayloadWithPads);
            paddedFlowStateEthernet.limit(SAMPLE_FLOW_STATE_PAYLOAD_SIZE);

            byte[] empty = Arrays.copyOf(emptyPacket, emptyPacket.length);
            ByteBuffer emptyPacketBuff = ByteBuffer.wrap(empty);
            short emptyChecksum = IPv4.computeChecksum(emptyPacket,
                                                       FlowStateEthernet.FLOW_STATE_IP_HEADER_OFFSET,
                                                       IPv4.MIN_HEADER_LEN,
                                                       FlowStateEthernet.FLOW_STATE_IP_CHECKSUM_OFFSET);

            emptyPacketBuff.putShort(
                    FlowStateEthernet.FLOW_STATE_IP_CHECKSUM_OFFSET,
                    emptyChecksum);

            byte[] sample = Arrays.copyOf(samplePacket, samplePacket.length);
            ByteBuffer samplePacketBuff = ByteBuffer.wrap(sample);
            short sampleChecksum =
                    IPv4.computeChecksum(samplePacket,
                            FlowStateEthernet.FLOW_STATE_IP_HEADER_OFFSET,
                            IPv4.MIN_HEADER_LEN,
                            FlowStateEthernet.FLOW_STATE_IP_CHECKSUM_OFFSET);

            samplePacketBuff.putShort(
                FlowStateEthernet.FLOW_STATE_IP_CHECKSUM_OFFSET,
                sampleChecksum);

            return Arrays.asList(new Object[][]{
                    {empty, emptyFlowStateEthernet},
                    {sample, sampleFlowStateEthernet},
                    {sample, paddedFlowStateEthernet}
            });
        }

        @Test
        public void TestSerialize() throws Exception {
            byte[] serialized = expected.serialize();
            ByteBuffer.wrap(serialized).putShort(FlowStateEthernet.FLOW_STATE_UDP_CHECKSUM_OFFSET, (short) 0);
            Assert.assertArrayEquals(data, serialized);
        }

        @Test
        public void TestSerializeWithByteBuffer() throws Exception {
            ByteBuffer buf = ByteBuffer.allocate(data.length);
            expected.serialize(buf);
            Assert.assertArrayEquals(data, buf.array());
        }
    }
}
