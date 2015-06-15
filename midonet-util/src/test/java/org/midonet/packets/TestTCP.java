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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@RunWith(Enclosed.class)
public class TestTCP {

    private static byte[] sampleHeader = new byte[] { (byte) 0xA8, (byte) 0xCA,
            (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02,
            (byte) 0x50, (byte) 0x02, (byte) 0x20, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    private static byte[] sampleHeaderWithOptions = new byte[] { (byte) 0xA8,
            (byte) 0xCA, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x02, (byte) 0x60, (byte) 0x02, (byte) 0x20, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

    private static byte[] tcpWithChecksum = new byte[] {
            (byte) 0x45, (byte) 0x00, (byte) 0x00, (byte) 0x34, (byte) 0x87,
            (byte) 0xb8, (byte) 0x00, (byte) 0x00, (byte) 0x37, (byte) 0x06,
            (byte) 0x8e, (byte) 0x64, (byte) 0xd8, (byte) 0x3a, (byte) 0xd2,
            (byte) 0xa1, (byte) 0xc0, (byte) 0xa8, (byte) 0x02, (byte) 0x23, // Ip header end
            (byte) 0x01, (byte) 0xbb, (byte) 0xd7, (byte) 0xf5, (byte) 0xd3,
            (byte) 0x14, (byte) 0x5b, (byte) 0xa5, (byte) 0x77, (byte) 0xdb,
            (byte) 0xf1, (byte) 0x25, (byte) 0x80, (byte) 0x10, (byte) 0x01,
            (byte) 0x5e, (byte) 0x5a, (byte) 0x5d, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x01, (byte) 0x08, (byte) 0x0a, (byte) 0x6d,
            (byte) 0x5a, (byte) 0x24, (byte) 0xf4, (byte) 0x4d, (byte) 0x1a,
            (byte) 0x5c, (byte) 0x85 };

    public static class TestTcpChecksumCalculation {

        @Test
        public void test() throws MalformedPacketException {
            IPv4 ipPkt = new IPv4();
            ipPkt.deserialize(ByteBuffer.wrap(tcpWithChecksum));
            ipPkt.clearChecksum();
            TCP tcp = (TCP)ipPkt.getPayload();
            tcp.clearChecksum();
            byte[] serializedTcpWithChecksum = ipPkt.serialize();
            assertArrayEquals(tcpWithChecksum, serializedTcpWithChecksum);
        }
    }

    public static class TestTcpPacketGeneral {

        @Test
        public void test() throws MalformedPacketException {
            TCP tcp = new TCP();
            tcp.setSourcePort(43210); // 0xA8CA
            tcp.setDestinationPort(1234);
            int seqNo = 1222333444;
            tcp.setSeqNo(seqNo);
            tcp.setAckNo(seqNo - 100);
            tcp.setFlags((short) 0xf000); // Sets the data offset to 15 words.
            tcp.setWindowSize((short) 4000);
            // Don't set checksum or urgent. Cksum computed during
            // serialization.
            Random rnd = new Random();
            byte[] options = new byte[40];
            rnd.nextBytes(options);
            tcp.setOptions(options);
            byte[] payload = new byte[1000];
            rnd.nextBytes(payload);
            tcp.setPayload(new Data(payload));
            byte[] segmentBytes = tcp.serialize();
            assertEquals(segmentBytes.length, payload.length + 60);
            // Deserialize the whole segment.
            TCP tcp2 = new TCP();
            ByteBuffer bb = ByteBuffer.wrap(segmentBytes, 0,
                    segmentBytes.length);
            tcp2.deserialize(bb);
            assertEquals(tcp, tcp2);
            // Deserialize the segment truncated after the options - 150 bytes.
            byte[] truncated = Arrays.copyOf(segmentBytes, 150);
            tcp2 = new TCP();
            bb = ByteBuffer.wrap(truncated, 0, truncated.length);
            tcp2.deserialize(bb);
            tcp.setPayload(new Data(Arrays.copyOf(payload, 90)));
            assertEquals(tcp, tcp2);

            // Deserialize the segment truncated before the options - 50 bytes.
            // This should throw MalformedPacketException
            truncated = Arrays.copyOf(segmentBytes, 50);
            tcp2 = new TCP();
            bb = ByteBuffer.wrap(truncated, 0, truncated.length);
            try {
                tcp2.deserialize(bb);
                Assert.fail("MalformedPacketException not thrown with "
                        + "truncated options.");
            } catch (MalformedPacketException ex) {
                // Success
            }
        }
    }

    @RunWith(Parameterized.class)
    public static class TestTcpValidPacket {

        private final byte[] data;
        private final TCP expected;

        public TestTcpValidPacket(byte[] data, TCP expected) {
            this.data = data;
            this.expected = expected;
        }

        private static TCP copyTcp(TCP packet) {
            TCP copy = new TCP();
            copy.setSourcePort(packet.getSourcePort());
            copy.setDestinationPort(packet.getDestinationPort());
            copy.setChecksum(packet.getChecksum());
            copy.setSeqNo(packet.getSeqNo());
            copy.setAckNo(packet.getAckNo());
            copy.setFlags(packet.getFlags());
            copy.setWindowSize(packet.getWindowSize());
            copy.setUrgent(packet.getUrgent());
            copy.setOptions(packet.getOptions());
            copy.setPayload(packet.getPayload());
            return copy;
        }

        private static Data createData(byte[] data, int from, int to) {
            Data d = new Data();
            d.setData(Arrays.copyOfRange(data, from, to));
            return d;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {

            // TCP object to use for testing.
            TCP templateTcp = new TCP();
            templateTcp.setSourcePort(43210);
            templateTcp.setDestinationPort((short) 8);
            templateTcp.setChecksum((short) 0);
            templateTcp.setSeqNo(1);
            templateTcp.setAckNo(2);
            templateTcp.setFlags((short) 0x5002);
            templateTcp.setWindowSize((short) 0x2000);
            templateTcp.setUrgent((short) 0);

            // For empty payload
            byte[] headerOnly = Arrays
                    .copyOf(sampleHeader, sampleHeader.length);

            // One byte payload
            byte[] oneByte = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 1);
            TCP oneByteTcp = copyTcp(templateTcp);
            oneByteTcp.setPayload(createData(oneByte, 20, 21));

            // Large payload
            byte[] maxLen = Arrays.copyOf(sampleHeader, 0xFFFF);
            TCP maxLenTcp = copyTcp(templateTcp);
            maxLenTcp.setPayload(createData(maxLen, 20, 0xFFFF));

            // For empty payload with options
            byte[] headerOnlyWithOptions = Arrays.copyOf(
                    sampleHeaderWithOptions, sampleHeaderWithOptions.length);
            TCP headerOnlyWithOptionsTcp = copyTcp(templateTcp);
            headerOnlyWithOptionsTcp.setOptions(Arrays.copyOfRange(
                    sampleHeaderWithOptions, 20, 24));
            headerOnlyWithOptionsTcp.setFlags((short) 0x6002);

            // One byte payload with options
            byte[] oneByteWithOptions = Arrays.copyOf(sampleHeaderWithOptions,
                    sampleHeaderWithOptions.length + 1);
            TCP oneByteWithOptionsTcp = copyTcp(headerOnlyWithOptionsTcp);
            oneByteWithOptionsTcp.setPayload(createData(oneByteWithOptions, 24,
                    25));

            // Large payload with options
            byte[] maxLenWithOptions = Arrays.copyOf(sampleHeaderWithOptions,
                    0xFFFF);
            TCP maxLenWithOptionsTcp = copyTcp(headerOnlyWithOptionsTcp);
            maxLenWithOptionsTcp.setPayload(createData(maxLenWithOptions, 24,
                    0xFFFF));

            Object[][] data = new Object[][] { { headerOnly, templateTcp },
                    { oneByte, oneByteTcp }, { maxLen, maxLenTcp },
                    { headerOnlyWithOptions, headerOnlyWithOptionsTcp },
                    { oneByteWithOptions, oneByteWithOptionsTcp },
                    { maxLenWithOptions, maxLenWithOptionsTcp } };

            return Arrays.asList(data);
        }

        @Test
        public void TestDeserialize() throws Exception {

            // Test
            ByteBuffer buff = ByteBuffer.wrap(this.data);
            TCP packet = new TCP();
            packet.deserialize(buff);

            // Check
            Assert.assertEquals(expected.getSourcePort(),
                    packet.getSourcePort());
            Assert.assertEquals(expected.getDestinationPort(),
                    packet.getDestinationPort());
            Assert.assertEquals(expected.getChecksum(), packet.getChecksum());
            Assert.assertEquals(expected.getSeqNo(), packet.getSeqNo());
            Assert.assertEquals(expected.getAckNo(), packet.getAckNo());
            Assert.assertEquals(expected.getFlags(), packet.getFlags());
            Assert.assertEquals(expected.getWindowSize(),
                    packet.getWindowSize());
            Assert.assertEquals(expected.getUrgent(), packet.getUrgent());
            Assert.assertArrayEquals(expected.getOptions(), packet.getOptions());
            Assert.assertEquals(expected.getPayload(), packet.getPayload());
            if (packet.getPayload() != null) {
                Assert.assertEquals(packet, packet.getPayload().getParent());
            }
        }

        @Test
        public void TestTCPFlags() throws Exception {
            TCP packet = new TCP();

            List<TCP.Flag> flags = new ArrayList<TCP.Flag>();
            flags.add(TCP.Flag.Ack);
            flags.add(TCP.Flag.Syn);

            // set the Ack and Syn flags
            packet.setFlags(flags);
            Assert.assertTrue(packet.getFlag(TCP.Flag.Ack));
            Assert.assertTrue(packet.getFlag(TCP.Flag.Syn));
            Assert.assertTrue(packet.getFlags() == TCP.Flag.allOf(flags));

            // remove the Ack flag
            packet.setFlag(TCP.Flag.Ack, false);
            Assert.assertFalse(packet.getFlag(TCP.Flag.Ack));
            Assert.assertTrue(packet.getFlag(TCP.Flag.Syn));
            Assert.assertFalse(packet.getFlags() == TCP.Flag.allOf(flags));

            // remove the Syn flag
            packet.setFlags((short) (packet.getFlags() & ~TCP.Flag.Syn.bit));
            Assert.assertFalse(packet.getFlag(TCP.Flag.Ack));
            Assert.assertFalse(packet.getFlag(TCP.Flag.Syn));
            Assert.assertFalse(packet.getFlags() == TCP.Flag.allOf(flags));

            Assert.assertTrue(packet.getFlags() == 0);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestTcpMalformedPacket {

        private final byte[] data;

        public TestTcpMalformedPacket(byte[] data) {
            this.data = data;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {

            // Empty
            byte[] empty = new byte[] {};

            // One byte packet
            byte[] oneByte = Arrays.copyOf(sampleHeader, 1);

            // 19 bytes
            byte[] oneByteLess = Arrays.copyOf(sampleHeader,
                    sampleHeader.length - 1);

            // Bad header len
            byte[] badHeaderLen = Arrays.copyOf(sampleHeader,
                    sampleHeader.length);
            badHeaderLen[12] = 0x10;

            // Bad options len
            byte[] badOptionsLen = Arrays.copyOf(sampleHeader,
                    sampleHeader.length);
            badOptionsLen[12] = (byte) 0xF0;

            Object[][] data = new Object[][] { { empty }, { oneByte },
                    { oneByteLess }, { badHeaderLen }, { badOptionsLen } };

            return Arrays.asList(data);
        }

        @Test(expected = MalformedPacketException.class)
        public void TestDeserializeProtoAddrLenTooBig() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.data);
            TCP packet = new TCP();
            packet.deserialize(buff);
        }
    }

}
