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
public class TestICMP {

    private static byte[] icmpBytes = new byte[] { (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0xb2, (byte) 0x78, (byte) 0x00,
            (byte) 0x01, (byte) 0xf8, (byte) 0x59, (byte) 0x98, (byte) 0x4e,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xc6,
            (byte) 0xec, (byte) 0x0b, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x11, (byte) 0x12,
            (byte) 0x13, (byte) 0x14, (byte) 0x15, (byte) 0x16, (byte) 0x17,
            (byte) 0x18, (byte) 0x19, (byte) 0x1a, (byte) 0x1b, (byte) 0x1c,
            (byte) 0x1d, (byte) 0x1e, (byte) 0x1f, (byte) 0x20, (byte) 0x21,
            (byte) 0x22, (byte) 0x23, (byte) 0x24, (byte) 0x25, (byte) 0x26,
            (byte) 0x27, (byte) 0x28, (byte) 0x29, (byte) 0x2a, (byte) 0x2b,
            (byte) 0x2c, (byte) 0x2d, (byte) 0x2e, (byte) 0x2f, (byte) 0x30,
            (byte) 0x31, (byte) 0x32, (byte) 0x33, (byte) 0x34, (byte) 0x35,
            (byte) 0x36, (byte) 0x37 };

    public static class TestICMPCheckSum {

        @Test
        public void testChecksum() throws MalformedPacketException {
            // The icmp packet is taken from wireshark, and the checksum bytes
            // have been zeroed out bytes[2] and bytes [3]. Wireshark claims
            // the checksum should be:
            short expCksum = 0x2c1e;

            // First, let's just try to compute the checksum using IPv4's
            // method.
            short cksum = IPv4.computeChecksum(icmpBytes, 0, icmpBytes.length,
                    -2);
            Assert.assertEquals(expCksum, cksum);

            // Now let's see if ICMP deserialization/serialization sets it
            // correctly.
            ICMP icmp = new ICMP();
            ByteBuffer bb = ByteBuffer.wrap(icmpBytes, 0, icmpBytes.length);
            icmp.deserialize(bb);
            byte[] bytes = icmp.serialize();
            // Verify that the checksum field has been set properly.
            Assert.assertEquals(0x2c, bytes[2]);
            Assert.assertEquals(0x1e, bytes[3]);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestICMPValidPacket {

        private final byte[] data;
        private final ICMP expected;

        public TestICMPValidPacket(byte[] data, ICMP expected) {
            this.data = data;
            this.expected = expected;
        }

        @Parameters
        public static Collection<Object[]> data() {

            ICMP icmpHeaderOnly = new ICMP();
            icmpHeaderOnly.setEchoReply((short) 0xB278, (short) 0x0001, null);
            byte[] onlyHeaderBytes = Arrays.copyOf(icmpBytes, 8);

            ICMP icmpWithData = new ICMP();
            byte[] data = Arrays.copyOfRange(icmpBytes, 8, icmpBytes.length);
            icmpWithData.setEchoReply((short) 0xB278, (short) 0x0001, data);

            Object[][] input = new Object[][] {
                    { onlyHeaderBytes, icmpHeaderOnly },
                    { icmpBytes, icmpWithData } };

            return Arrays.asList(input);
        }

        @Test
        public void TestDeserialize() throws Exception {
            ByteBuffer bb = ByteBuffer.wrap(this.data);
            ICMP packet = new ICMP();
            packet.deserialize(bb);

            Assert.assertEquals(expected.getType(), packet.getType());
            Assert.assertEquals(expected.getCode(), packet.getCode());
            Assert.assertEquals(expected.getChecksum(), packet.getChecksum());
            Assert.assertEquals(expected.getQuench(), packet.getQuench());
            Assert.assertArrayEquals(expected.getData(), packet.getData());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestICMPMalformedPacket {

        private final byte[] data;

        public TestICMPMalformedPacket(byte[] data) {
            this.data = data;
        }

        @Parameters
        public static Collection<Object[]> data() {

            Object[][] input = new Object[][] { { new byte[] {} },
                    { Arrays.copyOf(icmpBytes, 7) } };

            return Arrays.asList(input);
        }

        @Test(expected = MalformedPacketException.class)
        public void testDeserializeEmptyPacket()
                throws MalformedPacketException {
            ByteBuffer bb = ByteBuffer.wrap(this.data);
            ICMP packet = new ICMP();
            packet.deserialize(bb);
        }
    }

}
