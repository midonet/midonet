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
public class TestARP {

    private static byte[] samplePacket = new byte[] { (byte) 0x00, (byte) 0x01,
            (byte) 0x08, (byte) 0x00, (byte) 0x06, (byte) 0x04, (byte) 0x00,
            (byte) 0x01, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04,
            (byte) 0x05, (byte) 0x06, (byte) 0x0A, (byte) 0x0B, (byte) 0x0C,
            (byte) 0x0D, (byte) 0x06, (byte) 0x05, (byte) 0x04, (byte) 0x03,
            (byte) 0x02, (byte) 0x01, (byte) 0x0D, (byte) 0x0C, (byte) 0x0B,
            (byte) 0x0A, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    @RunWith(Parameterized.class)
    public static class TestArpValidPacket {

        private final byte[] data;
        private final ARP expected;

        public TestArpValidPacket(byte[] data, ARP expected) {
            this.data = data;
            this.expected = expected;
        }

        private static ARP copyArp(ARP packet) {
            ARP copy = new ARP();
            copy.setOpCode(packet.getOpCode());
            copy.setHardwareType(packet.getHardwareType());
            copy.setProtocolType(packet.getProtocolType());
            copy.setHardwareAddressLength(packet.getHardwareAddressLength());
            copy.setProtocolAddressLength(packet.getProtocolAddressLength());
            copy.setSenderHardwareAddress(packet.getSenderHardwareAddress());
            copy.setSenderProtocolAddress(packet.getSenderProtocolAddress());
            copy.setTargetHardwareAddress(packet.getTargetHardwareAddress());
            copy.setTargetProtocolAddress(packet.getTargetProtocolAddress());
            return copy;
        }

        @Parameters
        public static Collection<Object[]> data() {

            ARP arp = new ARP();
            arp.setOpCode((short) 0x01);
            arp.setHardwareType((short) 0x01);
            arp.setProtocolType((short) 0x0800);
            arp.setHardwareAddressLength((byte) 0x06);
            arp.setProtocolAddressLength((byte) 0x04);
            arp.setSenderHardwareAddress(new MAC(Arrays.copyOfRange(
                    samplePacket, 8, 14)));
            arp.setSenderProtocolAddress(Arrays.copyOfRange(samplePacket, 14,
                    18));
            arp.setTargetHardwareAddress(new MAC(Arrays.copyOfRange(
                    samplePacket, 18, 24)));
            arp.setTargetProtocolAddress(Arrays.copyOfRange(samplePacket, 24,
                    28));

            Object[][] data = new Object[][] { { samplePacket, arp } };

            return Arrays.asList(data);
        }

        @Test
        public void testDeserialize() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.data);
            ARP packet = new ARP();
            packet.deserialize(buff);

            Assert.assertEquals(expected.getHardwareType(),
                    packet.getHardwareType());
            Assert.assertEquals(expected.getProtocolType(),
                    packet.getProtocolType());
            Assert.assertEquals(expected.getOpCode(), packet.getOpCode());
            Assert.assertEquals(expected.getSenderHardwareAddress(),
                    packet.getSenderHardwareAddress());
            Assert.assertEquals(expected.getTargetHardwareAddress(),
                    packet.getTargetHardwareAddress());
            Assert.assertArrayEquals(expected.getSenderProtocolAddress(),
                    packet.getSenderProtocolAddress());
            Assert.assertArrayEquals(expected.getTargetProtocolAddress(),
                    packet.getTargetProtocolAddress());
            Assert.assertEquals(expected.getHardwareAddressLength(),
                    packet.getHardwareAddressLength());
            Assert.assertEquals(expected.getProtocolAddressLength(),
                    packet.getProtocolAddressLength());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestArpMalformedPacket {

        private final byte[] data;

        public TestArpMalformedPacket(byte[] data) {
            this.data = data;
        }

        @Parameters
        public static Collection<Object[]> data() {

            // Empty
            byte[] empty = new byte[] {};

            // One byte packet
            byte[] oneByte = new byte[] { (byte) 0x00 };

            // 27 bytes
            byte[] oneByteLess = Arrays.copyOf(samplePacket, 27);

            // Zero length proto addr
            byte[] zeroByteProtoAddr = Arrays.copyOf(samplePacket,
                    samplePacket.length);
            zeroByteProtoAddr[5] = (byte) 0x00;

            // One byte length proto addr
            byte[] oneByteProtoAddr = Arrays.copyOf(samplePacket,
                    samplePacket.length);
            oneByteProtoAddr[5] = (byte) 0x01;

            Object[][] data = new Object[][] { { empty }, { oneByte },
                    { oneByteLess }, { zeroByteProtoAddr },
                    { oneByteProtoAddr } };

            return Arrays.asList(data);
        }

        @Test(expected = MalformedPacketException.class)
        public void TestDeserializeProtoAddrLenTooBig() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.data);
            ARP packet = new ARP();
            packet.deserialize(buff);
        }
    }
}
