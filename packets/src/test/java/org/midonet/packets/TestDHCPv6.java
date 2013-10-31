package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Assert;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class TestDHCPv6 {

    public static class TestDHCPv6General {

        public final static byte[] dhcpv6PktData =
            new byte[] {
                    /* First the DHCPv6 msg header */
                    //msg type: SOLICIT
                    (byte) 0x01,
                    //transaction id:
                    (byte) 0x8E, (byte) 0x06, (byte) 0x50,
                    /* Next is the options */
                    /* Option 1) CLIENTID */
                    // Client Identifier
                    (byte) 0x00, (byte) 0x01,
                    // Len: 14
                    (byte) 0x00, (byte) 0x0E,
                    // DUID type: link-layer address plus time (1)
                    (byte) 0x00, (byte) 0x01,
                    // Hardware Type: Ethernet (1)
                    (byte) 0x00, (byte) 0x01,
                    // Time: Apr 8, 2013 13:58:58 JST
                    (byte) 0x18, (byte) 0xF5, (byte) 0x0B, (byte) 0x12,
                    // Link-layer address: CA:A3:C5:36:36:84
                    (byte) 0xCA, (byte) 0xA3, (byte) 0xC5, (byte) 0x36, (byte) 0x36, (byte) 0x84,
                    /* Option 2) ORO */
                    // Option Request (6)
                    (byte) 0x00, (byte) 0x06,
                    // Len: 8
                    (byte) 0x00, (byte) 0x08,
                    // Requested Option Code: Domain Search List
                    (byte) 0x00, (byte) 0x18,
                    // Requested Option Code: Fully Qualified Domain Name
                    (byte) 0x00, (byte) 0x27,
                    // Requested Option Code: DNS recursive name server
                    (byte) 0x00, (byte) 0x17,
                    // Requested Option Code: Simple Network Time Protocol Server
                    (byte) 0x00, (byte) 0x1F,
                    /* Option 3) ELAPSED_TIME */
                    // Elapsed Time
                    (byte) 0x00, (byte) 0x08,
                    // Len: 2
                    (byte) 0x00, (byte) 0x02,
                    // elapsed time: 32360 ms
                    (byte) 0x0C, (byte) 0xA4,
                    /* Option 4) IA_NA */
                    // Identity Association for Non-temporary Address (3)
                    (byte) 0x00, (byte) 0x03,
                    // Len: 12
                    (byte) 0x00, (byte) 0x0C,
                    // IAID
                    (byte) 0x5D, (byte) 0x9E, (byte) 0x88, (byte) 0x0D,
                    // T1: 3600
                    (byte) 0x00, (byte) 0x00, (byte) 0x0E, (byte) 0x10,
                    // T2: 5400
                    (byte) 0x00, (byte) 0x00, (byte) 0x15, (byte) 0x18 };

        @Test
        public void testSerialization() {

            DHCPv6 dhcpv6Pkt = new DHCPv6();
            // Deserialize the whole packet.
            ByteBuffer bb = ByteBuffer.wrap(dhcpv6PktData, 0, dhcpv6PktData.length);
            dhcpv6Pkt.deserialize(bb);
            // Now re-serialize and verify we get the same bytes back.
            Assert.assertArrayEquals(dhcpv6PktData, dhcpv6Pkt.serialize());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestDHCPv6ValidPacket {
        private final byte[] input;
        private final DHCPv6 expected;

        public TestDHCPv6ValidPacket(byte[] input, DHCPv6 expected) {
            this.input = input;
            this.expected = expected;
        }

        @Parameters
        public static Collection<Object[]> data() {
            DHCPv6 dhcpV6 = new DHCPv6();

            dhcpV6.setMsgType((byte) 0x01);
            dhcpV6.setTransactionId(9307728);

            DHCPv6Option.ClientId optClId = new DHCPv6Option.ClientId();
            optClId.setCode((short) 0x0001);
            optClId.setLength((short) 0x000E);
            optClId.setDUID("00:01:00:01:18:F5:0B:12:CA:A3:C5:36:36:84");

            DHCPv6Option.Oro optOro = new DHCPv6Option.Oro();
            optOro.setCode((short) 0x0006);
            optOro.setLength((short) 0x0008);
            ArrayList<Short> codeList = new ArrayList<Short>();
            codeList.add((short) 0x0018);
            codeList.add((short) 0x0027);
            codeList.add((short) 0x0017);
            codeList.add((short) 0x001F);
            optOro.setOptCodes(codeList);

            DHCPv6Option.ElapsedTime optEt = new DHCPv6Option.ElapsedTime();
            optEt.setCode((short) 0x0008);
            optEt.setLength((short) 0x0002);
            optEt.setElapsedTime((short) 0x0CA4);

            DHCPv6Option.IANA optIANA = new DHCPv6Option.IANA();
            optIANA.setCode((short) 0x0003);
            optIANA.setLength((short) 0x000C);
            optIANA.setIAID(0x5D9E880D);
            optIANA.setT1(0x00000E10);
            optIANA.setT2(0x00001518);

            ArrayList<DHCPv6OptPacket> options = new ArrayList<DHCPv6OptPacket>();
            options.add(optClId);
            options.add(optOro);
            options.add(optEt);
            options.add(optIANA);

            dhcpV6.setOptions(options);
            Object[][] input = new Object[][]
                { { Arrays.copyOf(TestDHCPv6General.dhcpv6PktData,
                        TestDHCPv6General.dhcpv6PktData.length), dhcpV6 } } ;
            return Arrays.asList(input);
        }

        @Test
        public void TestDeserialize() {
            ByteBuffer buff = ByteBuffer.wrap(this.input);
            DHCPv6 dhcpv6 = new DHCPv6();
            dhcpv6.deserialize(buff);
            Assert.assertTrue(expected.equals(dhcpv6));
        }
    }
}
