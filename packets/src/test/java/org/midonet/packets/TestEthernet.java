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
public class TestEthernet {

    private static byte[] sampleHeader = new byte[]{
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06,
        (byte) 0x06, (byte) 0x05, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x01,
        (byte) 0x01, (byte) 0x01};

    private static byte[] sampleTpidHeader = new byte[]{
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06,
        (byte) 0x06, (byte) 0x05, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x01,
        (byte) 0x81, (byte) 0x00, //TPID
        (byte) 0x3F, (byte) 0xF0, //TCI
        (byte) 0x01, (byte) 0x01}; //Ethernet Type

    private static byte[] sampleQinQHeader = new byte[]{
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06,
        (byte) 0x06, (byte) 0x05, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x01,
        (byte) 0x88, (byte) 0xA8, //TPID
        (byte) 0x3F, (byte) 0xFF, //TCI
        (byte) 0x81, (byte) 0x00, //TPID
        (byte) 0x3F, (byte) 0xF0, //TCI
        (byte) 0x01, (byte) 0x01}; //Ethernet Type

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
            copy.setVlanIDs(packet.getVlanIDs());
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
            emptyData.setData(new byte[]{});
            packet.setPayload(emptyData);

            // For empty payload
            byte[] empty = Arrays.copyOf(sampleHeader, sampleHeader.length);

            // 1 byte ethernet frame.
            byte[] oneByte = Arrays.copyOf(sampleHeader,
                                           sampleHeader.length + 1);
            Ethernet oneByteEth = copyEthernet(packet);
            oneByteEth.setPayload(getPayload(oneByte, sampleHeader.length,
                                             sampleHeader.length + 1));

            // Typical min of 46 byte payload
            byte[] minPayload = Arrays.copyOf(sampleHeader,
                                              sampleHeader.length + 46);
            Ethernet minPayloadEth = copyEthernet(packet);
            minPayloadEth.setPayload(getPayload(minPayload, sampleHeader.length,
                                                sampleHeader.length + 46));

            // Typical max of 1500 byte payload
            byte[] maxPayload = Arrays.copyOf(sampleHeader,
                                              sampleHeader.length + 1500);
            Ethernet maxPayloadEth = copyEthernet(packet);
            maxPayloadEth.setPayload(getPayload(maxPayload, sampleHeader.length,
                                                sampleHeader.length + 1500));

            // Jumbo 9000 byte payload
            byte[] jumboPayload = Arrays.copyOf(sampleHeader,
                                                sampleHeader.length + 9000);
            Ethernet jumboPayloadEth = copyEthernet(packet);
            jumboPayloadEth.setPayload(
                getPayload(jumboPayload, sampleHeader.length,
                           sampleHeader.length + 9000));

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
            oneByteEthTpid.setPayload(
                getPayload(oneByteTpid, sampleTpidHeader.length,
                           sampleTpidHeader.length + 1));


            // Typical min of 46 byte payload with TPID
            byte[] minPayloadTpid = Arrays.copyOf(sampleTpidHeader,
                                                  sampleTpidHeader.length + 46);
            Ethernet minPayloadEthTpid = copyEthernet(packetTpid);
            minPayloadEthTpid.setPayload(
                getPayload(minPayloadTpid, sampleTpidHeader.length,
                           sampleTpidHeader.length + 46));

            // Typical max of 1500 byte payload with TPID
            byte[] maxPayloadTpid = Arrays.copyOf(sampleTpidHeader,
                                                  sampleTpidHeader.length + 1500);
            Ethernet maxPayloadEthTpid = copyEthernet(packetTpid);
            maxPayloadEthTpid.setPayload(
                getPayload(maxPayloadTpid, sampleTpidHeader.length,
                           sampleTpidHeader.length + 1500));

            // Jumbo 9000 byte payload with TPID
            byte[] jumboPayloadTpid = Arrays.copyOf(sampleTpidHeader,
                                                    sampleTpidHeader.length + 9000);
            Ethernet jumboPayloadEthTpid = copyEthernet(packetTpid);
            jumboPayloadEthTpid.setPayload(
                getPayload(jumboPayloadTpid, sampleTpidHeader.length,
                           sampleTpidHeader.length + 9000));

            Ethernet packetQinQ = copyEthernet(packet);
            packetQinQ.setVlanID((short) 0xFFF);
            packetQinQ.setPriorityCode((byte) 0x01);
            packetQinQ.setVlanID((short) 0xFF0);

            // For empty payload with TPID
            byte[] emptyQinQ = Arrays.copyOf(sampleQinQHeader,
                                             sampleQinQHeader.length);

            // 1 byte ethernet frame with QinQ
            byte[] oneByteQinQ = Arrays.copyOf(sampleQinQHeader,
                                               sampleQinQHeader.length + 1);
            Ethernet oneByteEthQinQ = copyEthernet(packetQinQ);
            oneByteEthQinQ.setPayload(getPayload(oneByteQinQ,
                                                 sampleQinQHeader.length,
                                                 sampleQinQHeader.length + 1));

            // Typical min of 46 byte payload with QinQ
            byte[] minPayloadQinQ = Arrays.copyOf(sampleQinQHeader,
                                                  sampleQinQHeader.length + 46);
            Ethernet minPayloadEthQinQ = copyEthernet(packetQinQ);
            minPayloadEthQinQ.setPayload(getPayload(minPayloadQinQ,
                                                    sampleQinQHeader.length,
                                                    sampleQinQHeader.length + 46));

            // Typical max of 1500 byte payload with QinQ
            byte[] maxPayloadQinQ = Arrays.copyOf(sampleQinQHeader,
                                                  sampleQinQHeader.length + 1504);
            Ethernet maxPayloadEthQinQ = copyEthernet(packetQinQ);
            maxPayloadEthQinQ.setPayload(getPayload(maxPayloadQinQ,
                                                    sampleQinQHeader.length,
                                                    sampleQinQHeader.length + 1504));

            // Jumbo 9000 byte payload with QinQ
            byte[] jumboPayloadQinQ = Arrays.copyOf(sampleQinQHeader,
                                                    sampleQinQHeader.length + 9004);
            Ethernet jumboPayloadEthQinQ = copyEthernet(packetQinQ);
            jumboPayloadEthQinQ.setPayload(
                getPayload(jumboPayloadQinQ, sampleQinQHeader.length,
                           sampleQinQHeader.length + 9004));


            Object[][] data = new Object[][]{{empty, packet},
                {oneByte, oneByteEth}, {minPayload, minPayloadEth},
                {maxPayload, maxPayloadEth},
                {jumboPayload, jumboPayloadEth},
                {emptyTpid, packetTpid}, {oneByteTpid, oneByteEthTpid},
                {minPayloadTpid, minPayloadEthTpid},
                {maxPayloadTpid, maxPayloadEthTpid},
                {jumboPayloadTpid, jumboPayloadEthTpid},
                {emptyQinQ, packetQinQ},
                {oneByteQinQ, oneByteEthQinQ},
                {minPayloadQinQ, minPayloadEthQinQ},
                {maxPayloadQinQ, maxPayloadEthQinQ},
                {jumboPayloadQinQ, jumboPayloadEthQinQ}};

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
            Assert.assertEquals(this.expected.getVlanIDs(),
                                packet.getVlanIDs());
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
            byte[] empty = new byte[]{};

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

            Object[][] data = new Object[][]{{empty}, {oneByte},
                {byteLess}, {tpidMissing}, {tpidByteLess}};

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
