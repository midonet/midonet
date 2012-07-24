/*
 * Copyright 2012 Midokura KK
 */

package com.midokura.packets;

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
public class TestEthernet {

    private static byte[] sampleHeader = new byte[] { (byte) 0x01, (byte) 0x02,
            (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x06,
            (byte) 0x05, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x01,
            (byte) 0x01, (byte) 0x01 };

    private static byte[] sampleTpidHeader = new byte[] { (byte) 0x01,
            (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06,
            (byte) 0x06, (byte) 0x05, (byte) 0x04, (byte) 0x03, (byte) 0x02,
            (byte) 0x01, (byte) 0x81, (byte) 0x00, (byte) 0x3F, (byte) 0xF0,
            (byte) 0x01, (byte) 0x01 };

    @RunWith(Parameterized.class)
    public static class TestEthernetValidPacket {

        private final byte[] data;
        private final Ethernet expected;

        public TestEthernetValidPacket(byte[] data, Ethernet expected) {
            this.data = data;
            this.expected = expected;
        }

        private static Ethernet copyEthernet(Ethernet packet) {
            Ethernet copy = new Ethernet();
            copy.setEtherType(packet.getEtherType());
            copy.setDestinationMACAddress(packet.getDestinationMACAddress());
            copy.setSourceMACAddress(packet.getSourceMACAddress());
            copy.setVlanID(packet.getVlanID());
            copy.setPriorityCode(packet.getPriorityCode());
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

            Ethernet packet = new Ethernet();
            packet.setDestinationMACAddress(new MAC(Arrays.copyOfRange(
                    sampleHeader, 0, 6)));
            packet.setSourceMACAddress(new MAC(Arrays.copyOfRange(sampleHeader,
                    6, 12)));
            packet.setEtherType((short) 0x0101);
            Data emptyData = new Data();
            emptyData.setData(new byte[] {});
            packet.setPayload(emptyData);

            // For empty payload
            byte[] empty = Arrays.copyOf(sampleHeader, sampleHeader.length);

            // 1 byte ethernet frame.
            byte[] oneByte = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 1);
            Ethernet oneByteEth = copyEthernet(packet);
            oneByteEth.setPayload(getPayload(oneByte, 14, 15));

            // Typical min of 46 byte payload
            byte[] minPayload = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 46);
            Ethernet minPayloadEth = copyEthernet(packet);
            minPayloadEth.setPayload(getPayload(minPayload, 14, 60));

            // Typical max of 1500 byte payload
            byte[] maxPayload = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 1500);
            Ethernet maxPayloadEth = copyEthernet(packet);
            maxPayloadEth.setPayload(getPayload(maxPayload, 14, 1514));

            // Jumbo 9000 byte payload
            byte[] jumboPayload = Arrays.copyOf(sampleHeader,
                    sampleHeader.length + 9000);
            Ethernet jumboPayloadEth = copyEthernet(packet);
            jumboPayloadEth.setPayload(getPayload(jumboPayload, 14, 9014));

            Ethernet packetTpid = copyEthernet(packet);
            packetTpid.setVlanID((short) 0xFF0);
            packetTpid.setPriorityCode((byte) 0x01);

            // For empty payload with TPID
            byte[] emptyTpid = Arrays.copyOf(sampleTpidHeader,
                    sampleTpidHeader.length);

            // 1 byte ethernet frame with TPID
            byte[] oneByteTpid = Arrays.copyOf(sampleTpidHeader,
                    sampleTpidHeader.length + 1);
            Ethernet oneByteEthTpid = copyEthernet(packetTpid);
            oneByteEthTpid.setPayload(getPayload(oneByteTpid, 18, 19));

            // Typical min of 46 byte payload with TPID
            byte[] minPayloadTpid = Arrays.copyOf(sampleTpidHeader,
                    sampleTpidHeader.length + 46);
            Ethernet minPayloadEthTpid = copyEthernet(packetTpid);
            minPayloadEthTpid.setPayload(getPayload(minPayloadTpid, 18, 64));

            // Typical max of 1500 byte payload with TPID
            byte[] maxPayloadTpid = Arrays.copyOf(sampleTpidHeader,
                    sampleTpidHeader.length + 1500);
            Ethernet maxPayloadEthTpid = copyEthernet(packetTpid);
            maxPayloadEthTpid.setPayload(getPayload(maxPayloadTpid, 18, 1518));

            // Jumbo 9000 byte payload with TPID
            byte[] jumboPayloadTpid = Arrays.copyOf(sampleTpidHeader,
                    sampleTpidHeader.length + 9000);
            Ethernet jumboPayloadEthTpid = copyEthernet(packetTpid);
            jumboPayloadEthTpid.setPayload(getPayload(jumboPayloadTpid, 18,
                    9018));

            Object[][] data = new Object[][] { { empty, packet },
                    { oneByte, oneByteEth }, { minPayload, minPayloadEth },
                    { maxPayload, maxPayloadEth },
                    { jumboPayload, jumboPayloadEth },
                    { emptyTpid, packetTpid }, { oneByteTpid, oneByteEthTpid },
                    { minPayloadTpid, minPayloadEthTpid },
                    { maxPayloadTpid, maxPayloadEthTpid },
                    { jumboPayloadTpid, jumboPayloadEthTpid } };

            return Arrays.asList(data);
        }

        @Test
        public void TestDeserialize() throws Exception {

            // Test
            ByteBuffer buff = ByteBuffer.wrap(this.data);
            Ethernet packet = new Ethernet();
            packet.deserialize(buff);

            // Check
            Assert.assertEquals(this.expected.getDestinationMACAddress(),
                    packet.getDestinationMACAddress());
            Assert.assertEquals(this.expected.getSourceMACAddress(),
                    packet.getSourceMACAddress());
            Assert.assertEquals(this.expected.getEtherType(),
                    packet.getEtherType());
            Assert.assertEquals(this.expected.getVlanID(), packet.getVlanID());
            Assert.assertEquals(this.expected.getPriorityCode(),
                    packet.getPriorityCode());

            Assert.assertEquals(this.expected.getPayload(),
                    packet.getPayload());
            if (packet.getPayload() != null) {
                Assert.assertEquals(packet, packet.getPayload().getParent());
            }
        }
    }

    @RunWith(Parameterized.class)
    public static class TestEthernetMalformedPacket {

        private final byte[] data;

        public TestEthernetMalformedPacket(byte[] data) {
            this.data = data;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {

            // Empty
            byte[] empty = new byte[] {};

            // One byte packet
            byte[] oneByte = Arrays.copyOf(sampleHeader, 1);

            // Missing 1 byte in header
            byte[] byteLess = Arrays.copyOf(sampleHeader,
                    sampleHeader.length - 1);

            // TPID flag set but no TPID field.
            byte[] tpidMissing = Arrays.copyOf(sampleHeader,
                    sampleHeader.length);
            tpidMissing[12] = (byte) 0x81;
            tpidMissing[13] = 0x00;

            // TPID field exists but missing a byte.
            byte[] tpidByteLess = Arrays.copyOf(sampleTpidHeader,
                    sampleTpidHeader.length - 1);

            Object[][] data = new Object[][] { { empty }, { oneByte },
                    { byteLess }, { tpidMissing }, { tpidByteLess } };

            return Arrays.asList(data);
        }

        @Test(expected = MalformedPacketException.class)
        public void testDeserialize() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.data);
            Ethernet packet = new Ethernet();
            packet.deserialize(buff);
        }
    }
}
