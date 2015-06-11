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

@RunWith(Enclosed.class)
public class TestIPv4 {

    // Sample test IP headers to use for testing.
    private static byte[] testHeader = new byte[] { (byte) 0x45, (byte) 0x00,
            (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x01, (byte) 0x5F,
            (byte) 0xFF, (byte) 0x01, (byte) 0xFD, (byte) 0x44, (byte) 0x2E,
            (byte) 0x0A, (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x0A,
            (byte) 0x00, (byte) 0x00, (byte) 0x03 };

    private static byte[] testHeaderWithOptions = new byte[] { (byte) 0x46,
            (byte) 0x00, (byte) 0x00, (byte) 0x18, (byte) 0x00, (byte) 0x01,
            (byte) 0x5F, (byte) 0xFF, (byte) 0x01, (byte) 0xFD, (byte) 0x44,
            (byte) 0x2E, (byte) 0x0A, (byte) 0x00, (byte) 0x00, (byte) 0x02,
            (byte) 0x0A, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

    public static class TestIPv4Checksum {

        @Test
        public void testCksumZeroArrays() {
            byte[] bytes = new byte[100];
            short expCksum = (short) 0xffff;
            short cksum = IPv4.computeChecksum(bytes, 0, 0, 0);
            Assert.assertEquals(expCksum, cksum);
            cksum = IPv4.computeChecksum(bytes, 0, 100, 100);
            Assert.assertEquals(expCksum, cksum);
            cksum = IPv4.computeChecksum(bytes, 1, 99, 100);
            Assert.assertEquals(expCksum, cksum);
        }

        @Test
        public void testCksumRealData() {
            // An ICMP echo reply from tcpdump with checksum zeroed
            // (bytes 2, 3).
            byte[] data = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0xb2, (byte) 0x78, (byte) 0x00,
                    (byte) 0x01, (byte) 0xf8, (byte) 0x59, (byte) 0x98,
                    (byte) 0x4e, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0xc6, (byte) 0xec, (byte) 0x0b,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x10, (byte) 0x11, (byte) 0x12,
                    (byte) 0x13, (byte) 0x14, (byte) 0x15, (byte) 0x16,
                    (byte) 0x17, (byte) 0x18, (byte) 0x19, (byte) 0x1a,
                    (byte) 0x1b, (byte) 0x1c, (byte) 0x1d, (byte) 0x1e,
                    (byte) 0x1f, (byte) 0x20, (byte) 0x21, (byte) 0x22,
                    (byte) 0x23, (byte) 0x24, (byte) 0x25, (byte) 0x26,
                    (byte) 0x27, (byte) 0x28, (byte) 0x29, (byte) 0x2a,
                    (byte) 0x2b, (byte) 0x2c, (byte) 0x2d, (byte) 0x2e,
                    (byte) 0x2f, (byte) 0x30, (byte) 0x31, (byte) 0x32,
                    (byte) 0x33, (byte) 0x34, (byte) 0x35, (byte) 0x36,
                    (byte) 0x37 };
            short cksum = IPv4.computeChecksum(data, 0, data.length, 0);
            data[0] = (byte) (cksum >> 8);
            data[1] = (byte) cksum;
            // Verify that the checksum field is ignored by getting the same
            // cksum.
            Assert.assertEquals(cksum,
                    IPv4.computeChecksum(data, 0, data.length, 0));
            // Now verify that when we don't ignore the cksum, we get zero.
            Assert.assertEquals(0,
                    IPv4.computeChecksum(data, 0, data.length, -2));

            // Repeat with a different subset of the array (and odd length).
            cksum = IPv4.computeChecksum(data, 0, 45, 0);
            // Set the checksum field.
            data[0] = (byte) (cksum >> 8);
            data[1] = (byte) cksum;
            // Now verify that when we don't ignore the cksum, we get zero.
            Assert.assertEquals(0, IPv4.computeChecksum(data, 0, 45, -2));
        }

        @Test
        public void testCksumRandomArrays() {
            Random rand = new Random(12345);
            for (int i = 0; i < 10; i++) {
                // Generate a random length between 100 and 1000
                int length = rand.nextInt(900) + 100;
                byte[] data = new byte[length];
                rand.nextBytes(data);
                short cksum = IPv4.computeChecksum(data, 0, data.length, 0);
                data[0] = (byte) (cksum >> 8);
                data[1] = (byte) cksum;
                // Verify that if we don't ignore the cksum, we get zero.
                Assert.assertEquals(0,
                        IPv4.computeChecksum(data, 0, data.length, -2));
            }
        }
    }

    public static class TestIPv4General {

        @Test
        public void testSerializationICMP() throws MalformedPacketException {
            // An IP packet containing an ICMP echo reply (checksums zeroed).
            byte[] ipBytes = new byte[] { (byte) 0x45, (byte) 0x00,
                    (byte) 0x00, (byte) 0x54, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
                    (byte) 0x00, (byte) 0x00, (byte) 0xc0, (byte) 0xa8,
                    (byte) 0x14, (byte) 0x01, (byte) 0xc0, (byte) 0xa8,
                    (byte) 0x14, (byte) 0x03, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0xb2, (byte) 0x78,
                    (byte) 0x00, (byte) 0x01, (byte) 0xf8, (byte) 0x59,
                    (byte) 0x98, (byte) 0x4e, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0xc6, (byte) 0xec,
                    (byte) 0x0b, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x11,
                    (byte) 0x12, (byte) 0x13, (byte) 0x14, (byte) 0x15,
                    (byte) 0x16, (byte) 0x17, (byte) 0x18, (byte) 0x19,
                    (byte) 0x1a, (byte) 0x1b, (byte) 0x1c, (byte) 0x1d,
                    (byte) 0x1e, (byte) 0x1f, (byte) 0x20, (byte) 0x21,
                    (byte) 0x22, (byte) 0x23, (byte) 0x24, (byte) 0x25,
                    (byte) 0x26, (byte) 0x27, (byte) 0x28, (byte) 0x29,
                    (byte) 0x2a, (byte) 0x2b, (byte) 0x2c, (byte) 0x2d,
                    (byte) 0x2e, (byte) 0x2f, (byte) 0x30, (byte) 0x31,
                    (byte) 0x32, (byte) 0x33, (byte) 0x34, (byte) 0x35,
                    (byte) 0x36, (byte) 0x37 };
            IPv4 ipPkt = new IPv4();
            // First deserialize/serialize the whole packet.
            ByteBuffer bb = ByteBuffer.wrap(ipBytes, 0, ipBytes.length);
            ipPkt.deserialize(bb);
            byte[] bytes = ipPkt.serialize();
            // Verify the checksums.
            Assert.assertEquals(0x11, bytes[10]);
            Assert.assertEquals(0x55, bytes[11]);
            Assert.assertEquals(0x2c, bytes[22]);
            Assert.assertEquals(0x1e, bytes[23]);
            ipBytes[10] = 0x11;
            ipBytes[11] = 0x55;
            ipBytes[22] = 0x2c;
            ipBytes[23] = 0x1e;
            // Once checksums have been filled, the arrays should be equal.
            Assert.assertArrayEquals(ipBytes, bytes);

            // Now deserialize/serialize the same packet from a
            // larger-than-needed buffer.
            byte[] longBuffer = Arrays.copyOf(ipBytes, ipBytes.length + 100);
            ipPkt = new IPv4();
            bb = ByteBuffer.wrap(longBuffer);
            ipPkt.deserialize(bb);
            Assert.assertEquals(longBuffer.length, ipPkt.totalLength);

            // Now deserialize/serialize an incomplete packet
            byte[] truncatedIpBytes = Arrays.copyOf(ipBytes, ipBytes.length - 20);
            ipPkt = new IPv4();
            bb = ByteBuffer.wrap(truncatedIpBytes);
            ipPkt.deserialize(bb);
            Assert.assertArrayEquals(truncatedIpBytes, ipPkt.serialize());
        }

        @Test
        public void testSerializationDHCP() throws MalformedPacketException {
            // A DHCP Request packet from tcpdump, starting with IP headers.
            byte[] ipHdrData = new byte[] { (byte) 0x45, (byte) 0x10, (byte) 0x01,
                    (byte) 0x48, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x10, (byte) 0x11, (byte) 0xa9,
                    (byte) 0x96, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0x00, (byte) 0x44, (byte) 0x00,
                    (byte) 0x43, (byte) 0x01, (byte) 0x34, (byte) 0x70,
                    (byte) 0x16 };
            byte[] data = Arrays.copyOf(ipHdrData, ipHdrData.length +
                                            TestDHCP.TestDHCPGeneral.dhcpPktData.length);
            System.arraycopy(TestDHCP.TestDHCPGeneral.dhcpPktData, 0, data,
                             ipHdrData.length, TestDHCP.TestDHCPGeneral.dhcpPktData.length);
            IPv4 ipPkt = new IPv4();
            ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
            // Deserialize/serialize the whole packet.
            ipPkt.deserialize(bb);
            // Basic sanity check: IPv4 contains a UDP packet from port 68 to
            // 67.
            Assert.assertEquals(UDP.PROTOCOL_NUMBER, ipPkt.getProtocol());
            UDP udpPkt = (UDP) ipPkt.getPayload();
            Assert.assertEquals(68, udpPkt.getSourcePort());
            Assert.assertEquals(67, udpPkt.getDestinationPort());
            // Now re-serialize and verify we get the same bytes back.
            Assert.assertArrayEquals(data, ipPkt.serialize());

            // Now deserializae/serialize the same packet from a larger-than-needed
            // buffer.
            byte[] longBuffer = Arrays.copyOf(data, data.length+100);
            int totalLength = ipPkt.totalLength;
            ipPkt = new IPv4();
            bb = ByteBuffer.wrap(longBuffer, 0, longBuffer.length);
            ipPkt.deserialize(bb);
            ipPkt.setTotalLength(totalLength);
            Assert.assertArrayEquals(data, ipPkt.serialize());

            // Now try a partial packet... lose 20 bytes from the IPv4.
            Arrays.fill(data, data.length-20, data.length, (byte)0);
            ipPkt = new IPv4();
            bb = ByteBuffer.wrap(data, 0, data.length - 20);
            ipPkt.deserialize(bb);
            Assert.assertArrayEquals(data, ipPkt.serialize());
        }

        @Test
        public void testSerializationMDNS() throws MalformedPacketException {
            // IPv4 packet from tcpdump with UDP/MDNS payload. Has been
            // truncated
            // to 114 bytes to simulate OpenFlow's 128 bytes which include
            // Ethernet.
            byte[] data = new byte[] { (byte) 0x45, (byte) 0x00, (byte) 0x00,
                    (byte) 0xf2, (byte) 0x00, (byte) 0x00, (byte) 0x40,
                    (byte) 0x00, (byte) 0xff, (byte) 0x11, (byte) 0xc5,
                    (byte) 0x53, (byte) 0xc0, (byte) 0xa8, (byte) 0x14,
                    (byte) 0x03, (byte) 0xe0, (byte) 0x00, (byte) 0x00,
                    (byte) 0xfb, (byte) 0x14, (byte) 0xe9, (byte) 0x14,
                    (byte) 0xe9, (byte) 0x00, (byte) 0xde, (byte) 0x4a,
                    (byte) 0x33, (byte) 0x00, (byte) 0x00, (byte) 0x84,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x05, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x09, (byte) 0x5f, (byte) 0x73,
                    (byte) 0x65, (byte) 0x72, (byte) 0x76, (byte) 0x69,
                    (byte) 0x63, (byte) 0x65, (byte) 0x73, (byte) 0x07,
                    (byte) 0x5f, (byte) 0x64, (byte) 0x6e, (byte) 0x73,
                    (byte) 0x2d, (byte) 0x73, (byte) 0x64, (byte) 0x04,
                    (byte) 0x5f, (byte) 0x75, (byte) 0x64, (byte) 0x70,
                    (byte) 0x05, (byte) 0x6c, (byte) 0x6f, (byte) 0x63,
                    (byte) 0x61, (byte) 0x6c, (byte) 0x00, (byte) 0x00,
                    (byte) 0x0c, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                    (byte) 0x00, (byte) 0x11, (byte) 0x94, (byte) 0x00,
                    (byte) 0x14, (byte) 0x0c, (byte) 0x5f, (byte) 0x77,
                    (byte) 0x6f, (byte) 0x72, (byte) 0x6b, (byte) 0x73,
                    (byte) 0x74, (byte) 0x61, (byte) 0x74, (byte) 0x69,
                    (byte) 0x6f, (byte) 0x6e, (byte) 0x04, (byte) 0x5f,
                    (byte) 0x74, (byte) 0x63, (byte) 0x70, (byte) 0xc0,
                    (byte) 0x23, (byte) 0xc0, (byte) 0x34, (byte) 0x00,
                    (byte) 0x0c, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                    (byte) 0x00, (byte) 0x11, (byte) 0x94, (byte) 0x00,
                    (byte) 0x20, (byte) 0x1d, (byte) 0x6c };
            IPv4 ipPkt = new IPv4();
            // Deserialize the whole packet.
            ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
            ipPkt.deserialize(bb);
            // Basic sanity check: IPv4 contains a UDP packet from/to port 5353.
            Assert.assertEquals(UDP.PROTOCOL_NUMBER, ipPkt.getProtocol());
            UDP udpPkt = (UDP) ipPkt.getPayload();
            Assert.assertEquals(5353, udpPkt.getSourcePort());
            Assert.assertEquals(5353, udpPkt.getDestinationPort());
            // Now re-serialize and verify we get the same bytes back. The
            // serialized array is longer than the original because the original
            // is truncated.
            byte[] expected = ipPkt.serialize();
            // Update IP and UDP length fields
            data[25] = (byte ) (data.length - 20); // UDP
            Assert.assertArrayEquals(data, expected);

            // NOTE: in this case we can't test for a buffer/byte-array that's
            // longer than the actual packet, because the packet has been
            // truncated.
            // We would not be able to distinguish excess bytes from real
            // payload.
        }

        @Test
        public void testSerializationSSH() throws MalformedPacketException {
            // SSH packet (starting with IPv4 headers) captured with tcpdump.
            byte[] data = new byte[] { (byte) 0x45, (byte) 0x10, (byte) 0x00,
                    (byte) 0x64, (byte) 0xec, (byte) 0xbc, (byte) 0x40,
                    (byte) 0x00, (byte) 0x40, (byte) 0x06, (byte) 0x60,
                    (byte) 0xcf, (byte) 0xc0, (byte) 0xa8, (byte) 0x01,
                    (byte) 0x85, (byte) 0x0e, (byte) 0x80, (byte) 0x1c,
                    (byte) 0x4b, (byte) 0xe5, (byte) 0x0e, (byte) 0x00,
                    (byte) 0x16, (byte) 0x8d, (byte) 0x3a, (byte) 0x5d,
                    (byte) 0x09, (byte) 0x0f, (byte) 0x95, (byte) 0xc4,
                    (byte) 0xe3, (byte) 0x80, (byte) 0x18, (byte) 0xff,
                    (byte) 0xff, (byte) 0x0a, (byte) 0x46, (byte) 0x00,
                    (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x08,
                    (byte) 0x0a, (byte) 0x2f, (byte) 0x1c, (byte) 0x20,
                    (byte) 0x06, (byte) 0x0e, (byte) 0x20, (byte) 0x8d,
                    (byte) 0x38, (byte) 0xbd, (byte) 0xfc, (byte) 0xd7,
                    (byte) 0xa6, (byte) 0x8d, (byte) 0xc3, (byte) 0x06,
                    (byte) 0x93, (byte) 0x5f, (byte) 0xdf, (byte) 0x0e,
                    (byte) 0x11, (byte) 0x49, (byte) 0x4a, (byte) 0x68,
                    (byte) 0xdc, (byte) 0x30, (byte) 0x8a, (byte) 0x2b,
                    (byte) 0xdc, (byte) 0xb2, (byte) 0xb2, (byte) 0xd4,
                    (byte) 0x0e, (byte) 0xea, (byte) 0xb5, (byte) 0x1e,
                    (byte) 0xf9, (byte) 0xd0, (byte) 0xdf, (byte) 0x26,
                    (byte) 0xbf, (byte) 0x56, (byte) 0xa6, (byte) 0x65,
                    (byte) 0x36, (byte) 0x07, (byte) 0x9c, (byte) 0x95,
                    (byte) 0x23, (byte) 0x9d, (byte) 0xd3, (byte) 0xeb,
                    (byte) 0xa7, (byte) 0x3c, (byte) 0x68, (byte) 0xa3,
                    (byte) 0xe3 };
            IPv4 ipPkt = new IPv4();
            // Deserialize the whole packet.
            ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
            ipPkt.deserialize(bb);
            // Basic sanity check: IPv4 contains a TCP packet to port 22 (ssh).
            Assert.assertEquals(TCP.PROTOCOL_NUMBER, ipPkt.getProtocol());
            TCP udpPkt = (TCP) ipPkt.getPayload();
            Assert.assertEquals(22, udpPkt.getDestinationPort());
            // Now re-serialize and verify we get the same bytes back. The
            // serialized array is longer than the original because the original
            // is truncated.
            byte[] expected = ipPkt.serialize();
            expected = Arrays.copyOf(expected, data.length);
            Assert.assertArrayEquals(data, expected);

            // Now deserializae/serialize the same packet from a larger-than-needed
            // buffer.
            byte[] longBuffer = Arrays.copyOf(data, data.length+100);
            ipPkt = new IPv4();
            bb = ByteBuffer.wrap(longBuffer, 0, longBuffer.length);
            ipPkt.deserialize(bb);
            Assert.assertEquals(longBuffer.length, ipPkt.totalLength);

            // Now try deserializaing/serializing a truncated packet.
            byte[] truncatedData = Arrays.copyOf(data, data.length - 30);
            ipPkt = new IPv4();
            bb = ByteBuffer.wrap(truncatedData);
            ipPkt.deserialize(bb);
            Assert.assertArrayEquals(truncatedData, ipPkt.serialize());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestIPv4ValidPacket {

        private final byte[] data;
        private final IPv4 expected;

        public TestIPv4ValidPacket(byte[] data, IPv4 expected) {
            this.data = data;
            this.expected = expected;
        }

        private static IPv4 copyIPv4(IPv4 packet) {
            IPv4 copy = new IPv4();
            copy.setVersion(packet.getVersion());
            copy.setHeaderLength(packet.getHeaderLength());
            copy.setDiffServ(packet.getDiffServ());
            copy.setTotalLength(packet.getTotalLength());
            copy.setIdentification(packet.getIdentification());
            copy.setFlags(packet.getFlags());
            copy.setFragmentOffset(packet.getFragmentOffset());
            copy.setTtl(packet.getTtl());
            copy.setProtocol(packet.getProtocol());
            copy.setChecksum(packet.getChecksum());
            copy.setSourceAddress(packet.getSourceAddress());
            copy.setDestinationAddress(packet.getDestinationAddress());
            copy.setOptions(packet.getOptions());
            copy.setPayload(packet.getPayload());
            return copy;
        }

        private static Data createData(byte[] data, int from, int to) {
            Data d = new Data();
            d.setData(Arrays.copyOfRange(data, from, to));
            return d;
        }

        @Parameters
        public static Collection<Object[]> data() {

            // IPv4 object to use for testing.
            IPv4 templateIPv4 = new IPv4();
            templateIPv4.setVersion((byte) 4);
            templateIPv4.setHeaderLength((byte) 5);
            templateIPv4.setDiffServ((byte) 0);
            templateIPv4.setTotalLength(20);
            templateIPv4.setIdentification((short) 1);
            templateIPv4.setFlags((byte) 2);
            templateIPv4.setFragmentOffset((short) 0x1FFF);
            templateIPv4.setTtl((byte) 1);
            templateIPv4.setProtocol((byte) 0xFD);
            templateIPv4.setChecksum((short) 0x442E);
            templateIPv4.setSourceAddress(167772162);
            templateIPv4.setDestinationAddress(167772163);
            templateIPv4.setPayload(createData(new byte[] {}, 0, 0));

            // For empty payload
            byte[] headerOnly = Arrays.copyOf(testHeader, testHeader.length);

            // One byte payload
            byte[] oneByte = Arrays.copyOf(testHeader, testHeader.length + 1);
            oneByte[3] = 0x15;
            IPv4 oneByteIPv4 = copyIPv4(templateIPv4);
            oneByteIPv4.setTotalLength(testHeader.length + 1);
            oneByteIPv4.setPayload(createData(oneByte, 20, 21));

            // Large payload
            byte[] maxLen = Arrays.copyOf(testHeader, 0xFFFF);
            maxLen[2] = (byte) 0xFF;
            maxLen[3] = (byte) 0xFF;
            IPv4 maxLenIPv4 = copyIPv4(templateIPv4);
            maxLenIPv4.setTotalLength(0xFFFF);
            maxLenIPv4.setPayload(createData(maxLen, 20, 0xFFFF));

            // Payload truncated - must support this currently
            byte[] truncated = Arrays.copyOf(testHeader, testHeader.length + 1);
            truncated[3] = (byte) 0x16;
            IPv4 truncatedIPv4 = copyIPv4(templateIPv4);
            truncatedIPv4.setTotalLength(testHeader.length + 2);
            truncatedIPv4.setPayload(createData(truncated, 20, 21));

            // Bad total len
            byte[] totalLenTooSmall = Arrays.copyOf(testHeader,
                    testHeader.length + 2);
            totalLenTooSmall[3] = (byte) 0x15;
            IPv4 totalLenTooSmallIPv4 = copyIPv4(templateIPv4);
            totalLenTooSmallIPv4.setTotalLength(testHeader.length + 2);
            totalLenTooSmallIPv4.setPayload(createData(totalLenTooSmall, 20, 22));

            // For empty payload with options
            byte[] headerOnlyWithOptions = Arrays.copyOf(testHeaderWithOptions,
                    testHeaderWithOptions.length);
            IPv4 headerOnlyWithOptionsIPv4 = copyIPv4(templateIPv4);
            headerOnlyWithOptionsIPv4.setOptions(Arrays.copyOfRange(
                    headerOnlyWithOptions, 20, 24));
            headerOnlyWithOptionsIPv4.setHeaderLength((byte) 6);
            headerOnlyWithOptionsIPv4
                    .setTotalLength(testHeaderWithOptions.length);

            // One byte payload with options
            byte[] oneByteWithOptions = Arrays.copyOf(testHeaderWithOptions,
                    testHeaderWithOptions.length + 1);
            oneByteWithOptions[3] = 0x19;
            IPv4 oneByteWithOptionsIPv4 = copyIPv4(headerOnlyWithOptionsIPv4);
            oneByteWithOptionsIPv4
                    .setTotalLength(testHeaderWithOptions.length + 1);
            oneByteWithOptionsIPv4.setPayload(createData(oneByteWithOptions,
                    24, 25));

            // Large payload with options
            byte[] maxLenWithOptions = Arrays.copyOf(testHeaderWithOptions,
                    0xFFFF);
            maxLenWithOptions[2] = (byte) 0xFF;
            maxLenWithOptions[3] = (byte) 0xFF;
            IPv4 maxLenWithOptionsIPv4 = copyIPv4(headerOnlyWithOptionsIPv4);
            maxLenWithOptionsIPv4.setTotalLength(0xFFFF);
            maxLenWithOptionsIPv4.setPayload(createData(maxLenWithOptions, 24,
                    0xFFFF));

            // Payload truncated - must support this currently
            byte[] truncatedWithOptions = Arrays.copyOf(testHeaderWithOptions,
                    testHeaderWithOptions.length + 1);
            truncatedWithOptions[3] = (byte) 0x1A;
            IPv4 truncatedWithOptionsIPv4 = copyIPv4(headerOnlyWithOptionsIPv4);
            truncatedWithOptionsIPv4
                    .setTotalLength(testHeaderWithOptions.length + 2);
            truncatedWithOptionsIPv4.setPayload(createData(
                    truncatedWithOptions, 24, 25));

            Object[][] data = new Object[][] {
                    { headerOnly, templateIPv4 },
                    { oneByte, oneByteIPv4 }, { maxLen, maxLenIPv4 },
                    { truncated, truncatedIPv4 },
                    { totalLenTooSmall, totalLenTooSmallIPv4 },
                    { headerOnlyWithOptions, headerOnlyWithOptionsIPv4 },
                    { oneByteWithOptions, oneByteWithOptionsIPv4 },
                    { maxLenWithOptions, maxLenWithOptionsIPv4 },
                    { truncatedWithOptions, truncatedWithOptionsIPv4 }
            };

            return Arrays.asList(data);
        }

        @Test
        public void testDeserialize() throws Exception {
            ByteBuffer bb = ByteBuffer.wrap(this.data);
            IPv4 packet = new IPv4();
            packet.deserialize(bb);

            Assert.assertEquals(expected.getVersion(), packet.getVersion());
            Assert.assertEquals(expected.getHeaderLength(),
                    packet.getHeaderLength());
            Assert.assertEquals(expected.getDiffServ(), packet.getDiffServ());
            Assert.assertEquals(expected.getTotalLength(),
                    packet.getTotalLength());
            Assert.assertEquals(expected.getIdentification(),
                    packet.getIdentification());
            Assert.assertEquals(expected.getFlags(), packet.getFlags());
            Assert.assertEquals(expected.getFragmentOffset(),
                    packet.getFragmentOffset());
            Assert.assertEquals(expected.getTtl(), packet.getTtl());
            Assert.assertEquals(expected.getProtocol(), packet.getProtocol());
            Assert.assertEquals(expected.getChecksum(), packet.getChecksum());
            Assert.assertEquals(expected.getSourceAddress(),
                    packet.getSourceAddress());
            Assert.assertEquals(expected.getDestinationAddress(),
                    packet.getDestinationAddress());
            Assert.assertArrayEquals(expected.getOptions(), packet.getOptions());
            Assert.assertEquals(expected.getPayload(), packet.getPayload());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestIPv4MalformedPacket {

        private final byte[] data;

        public TestIPv4MalformedPacket(byte[] data) {
            this.data = data;
        }

        @Parameters
        public static Collection<Object[]> data() {
            // Empty
            byte[] empty = new byte[] {};

            // One byte packet
            byte[] oneByte = Arrays.copyOf(testHeader, 1);

            // 19 bytes
            byte[] oneByteLess = Arrays.copyOf(testHeader,
                    testHeader.length - 1);

            // max + 1 bytes
            byte[] buffTooBig = Arrays.copyOf(testHeader, 0x10000);
            buffTooBig[2] = (byte) 0xFF;
            buffTooBig[3] = (byte) 0xFF;

            // Bad header len
            byte[] badHeaderLen = Arrays.copyOf(testHeader, testHeader.length);
            badHeaderLen[0] = (byte) 0x44;

            // Bad total len
            byte[] totalHeaderLenTooSmall = Arrays.copyOf(testHeader,
                    testHeader.length);
            totalHeaderLenTooSmall[2] = (byte) 0x00;
            totalHeaderLenTooSmall[3] = (byte) 0x13;

            Object[][] data = new Object[][] { { empty }, { oneByte },
                    { oneByteLess }, { badHeaderLen },
                    { totalHeaderLenTooSmall } };

            return Arrays.asList(data);
        }

        @Test(expected = MalformedPacketException.class)
        public void testDeserialize() throws Exception {
            ByteBuffer bb = ByteBuffer.wrap(this.data);
            IPv4 packet = new IPv4();
            packet.deserialize(bb);
        }
    }
}
