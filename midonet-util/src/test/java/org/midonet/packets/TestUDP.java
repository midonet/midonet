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
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Enclosed.class)
public class TestUDP {

    private static byte[] sampleHeader = new byte[] { (byte) 0xA8, (byte) 0xCA,
            (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x00 };

    public static class TestUdpPacketGeneral {

        @Test
        public void testLargeDatagramSerialization()
                throws MalformedPacketException {
            UDP udp = new UDP();
            udp.setDestinationPort(1234);
            udp.setSourcePort(43210); // 0xA8CA
            Random rnd = new Random();
            byte[] payload = new byte[50000];
            rnd.nextBytes(payload);
            udp.setPayload(new Data(payload));
            byte[] dgramBytes = udp.serialize();
            // Deserialize the whole datagram.
            udp = new UDP();
            ByteBuffer bb = ByteBuffer.wrap(dgramBytes, 0, dgramBytes.length);
            udp.deserialize(bb);
            assertEquals(udp.getLength() & 0xffff, payload.length + 8);
            Data pay2 = Data.class.cast(udp.getPayload());
            assertTrue(Arrays.equals(payload, pay2.getData()));
            // Deserialize the truncated datagram.
            byte[] truncated = Arrays.copyOf(dgramBytes, 150);
            udp = new UDP();
            bb = ByteBuffer.wrap(truncated, 0, truncated.length);
            udp.deserialize(bb);
            assertEquals(udp.getLength() & 0xffff, payload.length + 8);
            pay2 = Data.class.cast(udp.getPayload());
            assertTrue(Arrays.equals(
                    Arrays.copyOf(payload, truncated.length - 8),
                    pay2.getData()));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestUdpValidPacket {

        private final byte[] data;
        private final UDP expected;

        public TestUdpValidPacket(byte[] data, UDP expected) {
            this.data = data;
            this.expected = expected;
        }

        private static UDP copyUdp(UDP packet) {
            UDP copy = new UDP();
            copy.setSourcePort(packet.getSourcePort());
            copy.setDestinationPort(packet.getDestinationPort());
            copy.setChecksum(packet.getChecksum());
            copy.setLength(packet.getLength());
            copy.setPayload(packet.getPayload());
            return copy;
        }

        private static Data getPayload(byte[] data, int from, int to) {
            Data d = new Data();
            d.setData(Arrays.copyOfRange(data, from, to));
            return d;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {

            // UDP with header only - use this for testing.
            UDP templateUdp = new UDP();
            templateUdp.setSourcePort(43210); //0xA8CA
            templateUdp.setDestinationPort(8);
            templateUdp.setChecksum((short) 0);
            templateUdp.setLength(8);
            // TODO: Currently UDP always sets the payload to a Data object
            // even if there is no payload.  Consider skipping that and
            // keeping it null.
            templateUdp.setPayload(getPayload(new byte[] {}, 0, 0));

            // For empty payload
            byte[] headerOnly = Arrays
                    .copyOf(sampleHeader, sampleHeader.length);

            // One byte payload
            byte[] oneByte = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 1);
            oneByte[4] = 0x00;
            oneByte[5] = 0x09;
            UDP oneByteUdp = copyUdp(templateUdp);
            oneByteUdp.setLength(9);
            oneByteUdp.setPayload(getPayload(oneByte, 8, 9));

            // Max size payload
            byte[] maxLen = Arrays.copyOf(sampleHeader, 0xFFFF);
            maxLen[4] = (byte) 0xFF;
            maxLen[5] = (byte) 0xFF;
            UDP maxLenUdp = copyUdp(templateUdp);
            maxLenUdp.setLength(0xFFFF);
            maxLenUdp.setPayload(getPayload(maxLen, 8, 0xFFFF));

            // Truncated payload - for now, supported
            byte[] truncated = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 1);
            truncated[4] = 0x00;
            truncated[5] = 0x0A;
            UDP truncatedUdp = copyUdp(templateUdp);
            truncatedUdp.setLength(0xFFFF);
            truncatedUdp.setPayload(getPayload(truncated, 8, 9));

            // Payload size more than necessary
            byte[] moreThanReq = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 2);
            moreThanReq[4] = 0x00;
            moreThanReq[5] = 0x09;
            UDP moreThanReqUdp = copyUdp(templateUdp);
            moreThanReqUdp.setLength(0x09);
            moreThanReqUdp.setPayload(getPayload(moreThanReq, 8, 9));

            Object[][] input = new Object[][] { { headerOnly, templateUdp },
                    { oneByte, oneByteUdp }, { maxLen, maxLenUdp },
                    { truncated, truncatedUdp },
                    { moreThanReq, moreThanReqUdp } };

            return Arrays.asList(input);
        }

        @Test
        public void TestDeserialize() throws Exception {

            ByteBuffer buff = ByteBuffer.wrap(this.data);
            UDP packet = new UDP();
            packet.deserialize(buff);

            Assert.assertEquals(expected.getSourcePort(),
                    packet.getSourcePort());
            Assert.assertEquals(expected.getDestinationPort(),
                    packet.getDestinationPort());
            Assert.assertEquals(expected.getChecksum(), packet.getChecksum());
            Assert.assertEquals(expected.getPayload(), packet.getPayload());
            Assert.assertEquals(packet, packet.getPayload().getParent());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestUdpMalformedPacket {

        private final byte[] data;

        public TestUdpMalformedPacket(byte[] data) {
            this.data = data;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {

            // Empty
            byte[] empty = new byte[] {};

            // One byte packet
            byte[] oneByte = new byte[] { (byte) 0x00 };

            // 7 bytes
            byte[] oneByteLess = Arrays.copyOf(sampleHeader, 7);

            // 0XFFFF + 1 bytes
            byte[] oneByteMore = Arrays.copyOf(sampleHeader, 0xFFFF + 1);

            Object[][] input = new Object[][] { { empty }, { oneByte },
                    { oneByteLess }, { oneByteMore } };

            return Arrays.asList(input);
        }

        @Test(expected = MalformedPacketException.class)
        public void TestDeserializeProtoAddrLenTooBig() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.data);
            UDP packet = new UDP();
            packet.deserialize(buff);
        }
    }

}
