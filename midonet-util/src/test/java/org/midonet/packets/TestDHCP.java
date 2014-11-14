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

import org.junit.Assert;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class TestDHCP {

    public static class TestDHCPGeneral {

        public final static byte[] dhcpPktData =
            new byte[] { 
                    // op, htype, hlen, hops
                    (byte) 0x01, (byte) 0x01, (byte) 0x06, (byte) 0x00, 
                    // transaction ID
                    (byte) 0x2e, (byte) 0x86, (byte) 0xe1, (byte) 0x21,
                    // secs and flags
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, 
                    // Client IP addr.
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, 
                    // Your IP addr.
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, 
                    // Server IP addr.
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, 
                    // Your IP addr. (again?)
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, 
                    // Client MAC addr.
                    (byte) 0x02, (byte) 0x16, (byte) 0x3e, (byte) 0x26,
                    (byte) 0x14, (byte) 0x99, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    // Server name
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    // Filename
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    // Magic number
                    (byte) 0x63, (byte) 0x82, (byte) 0x53, (byte) 0x63, 
                    // Options
                    (byte) 0x35, (byte) 0x01, (byte) 0x03, (byte) 0x32, 
                    (byte) 0x04, (byte) 0xc0, (byte) 0xa8, (byte) 0x14,
                    (byte) 0x03, (byte) 0x37, (byte) 0x0a, (byte) 0x01,
                    (byte) 0x1c, (byte) 0x02, (byte) 0x03, (byte) 0x0f,
                    (byte) 0x06, (byte) 0x0c, (byte) 0x28, (byte) 0x29,
                    (byte) 0x2a, (byte) 0xff, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

        @Test
        public void testSerialization() throws MalformedPacketException {

            DHCP dhcpPkt = new DHCP();
            // Deserialize the whole packet.
            ByteBuffer bb = ByteBuffer.wrap(dhcpPktData, 0, dhcpPktData.length);
            dhcpPkt.deserialize(bb);
            // Now re-serialize and verify we get the same bytes back.
            Assert.assertArrayEquals(dhcpPktData, dhcpPkt.serialize());

            // Verify that deserialize/serialize from a padded buffer works.
            byte[] buffer = Arrays.copyOf(dhcpPktData, dhcpPktData.length + 100);
            dhcpPkt = new DHCP();
            bb = ByteBuffer.wrap(buffer, 0, buffer.length);
            dhcpPkt.deserialize(bb);
            Assert.assertArrayEquals(dhcpPktData, dhcpPkt.serialize());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestDHCPValidPacket {

        private final byte[] input;
        private final DHCP expected;

        public TestDHCPValidPacket(byte[] input, DHCP expected) {
            this.input = input;
            this.expected = expected;
        }

        private static DHCP copyDhcp(DHCP dhcp) {
            DHCP copy = new DHCP();
            copy.setOpCode(dhcp.getOpCode());
            copy.setHardwareType(dhcp.getHardwareType());
            copy.setHardwareAddressLength(dhcp.getHardwareAddressLength());
            copy.setHops(dhcp.getHops());
            copy.setTransactionId(dhcp.getTransactionId());
            copy.setSeconds(dhcp.getSeconds());
            copy.setFlags(dhcp.getFlags());
            copy.setClientIPAddress(dhcp.getClientIPAddress());
            copy.setYourIPAddress(dhcp.getYourIPAddress());
            copy.setServerIPAddress(dhcp.getServerIPAddress());
            copy.setGatewayIPAddress(dhcp.getGatewayIPAddress());
            copy.setClientHardwareAddress(new MAC(dhcp
                    .getClientHardwareAddress()));
            copy.setServerName(dhcp.getServerName());
            copy.setBootFileName(dhcp.getBootFileName());
            copy.setOptions(dhcp.getOptions());
            return copy;
        }

        @Parameters
        public static Collection<Object[]> data() {

            DHCP dhcp = new DHCP();
            dhcp.setOpCode((byte) 0x01);
            dhcp.setHardwareType((byte) 0x01);
            dhcp.setHardwareAddressLength((byte) 0x06);
            dhcp.setHops((byte) 0x00);
            dhcp.setTransactionId(0x2E86E121);
            dhcp.setSeconds((short) 0x00);
            dhcp.setFlags((short) 0x00);
            dhcp.setClientIPAddress(0x00);
            dhcp.setYourIPAddress(0x00);
            dhcp.setServerIPAddress(0x00);
            dhcp.setGatewayIPAddress(0x00);
            dhcp.setClientHardwareAddress(new MAC(new byte[] { (byte) 0x02,
                    (byte) 0x16, (byte) 0x3e, (byte) 0x26, (byte) 0x14,
                    (byte) 0x99 }));
            dhcp.setServerName("");
            dhcp.setBootFileName("");

            List<DHCPOption> options = new ArrayList<DHCPOption>();
            DHCPOption option = new DHCPOption();
            option.setCode((byte) 0x35);
            option.setLength((byte) 1);
            option.setData(new byte[] { (byte) 0xFF });
            options.add(option);
            DHCPOption endOption = new DHCPOption();
            endOption.setCode((byte) 0xFF);
            options.add(endOption);
            dhcp.setOptions(options);

            // With optins
            byte[] withOptions = Arrays.copyOf(TestDHCPGeneral.dhcpPktData, 244);
            withOptions[242] = (byte) 0xFF;
            withOptions[243] = (byte) 0xFF;

            // No options
            byte[] noOptions = Arrays.copyOf(TestDHCPGeneral.dhcpPktData, 240);
            DHCP noOptionsDhcp = copyDhcp(dhcp);
            noOptionsDhcp.setOptions(new ArrayList<DHCPOption>());

            Object[][] input = new Object[][] { { withOptions, dhcp },
                    { noOptions, noOptionsDhcp } };

            return Arrays.asList(input);
        }

        @Test
        public void TestDeserialize() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.input);
            DHCP dhcp = new DHCP();
            dhcp.deserialize(buff);

            Assert.assertEquals(expected.getOpCode(), dhcp.getOpCode());
            Assert.assertEquals(expected.getHardwareType(),
                    dhcp.getHardwareType());
            Assert.assertEquals(expected.getHardwareAddressLength(),
                    dhcp.getHardwareAddressLength());
            Assert.assertEquals(expected.getHops(), dhcp.getHops());
            Assert.assertEquals(expected.getTransactionId(),
                    dhcp.getTransactionId());
            Assert.assertEquals(expected.getSeconds(), dhcp.getSeconds());
            Assert.assertEquals(expected.getFlags(), dhcp.getFlags());
            Assert.assertEquals(expected.getClientIPAddress(),
                    dhcp.getClientIPAddress());
            Assert.assertEquals(expected.getYourIPAddress(),
                    dhcp.getYourIPAddress());
            Assert.assertEquals(expected.getServerIPAddress(),
                    dhcp.getServerIPAddress());
            Assert.assertArrayEquals(expected.getClientHardwareAddress(),
                    dhcp.getClientHardwareAddress());
            Assert.assertEquals(expected.getServerName(),
                    dhcp.getServerName());
            Assert.assertEquals(expected.getBootFileName(),
                    dhcp.getBootFileName());
            Assert.assertArrayEquals(expected.getOptions().toArray(),
                    dhcp.getOptions().toArray());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestDHCPMalformedPacket {

        private final byte[] input;

        public TestDHCPMalformedPacket(byte[] input) {
            this.input = input;
        }


        @Parameters
        public static Collection<Object[]> data() {

            byte[] cutOff = Arrays.copyOf(TestDHCPGeneral.dhcpPktData, 239);

            byte[] badHwLen = Arrays.copyOf(TestDHCPGeneral.dhcpPktData,
                                            TestDHCPGeneral.dhcpPktData.length);
            badHwLen[2] = 0x11;

            byte[] missingOptionVal = Arrays.copyOf(TestDHCPGeneral.dhcpPktData, 241);

            byte[] badOptionLen = Arrays.copyOf(TestDHCPGeneral.dhcpPktData, 
                                            TestDHCPGeneral.dhcpPktData.length);
            badOptionLen[241] = (byte) 0xFF;

            Object[][] input = new Object[][] { { new byte[] {} },
                    { cutOff }, { badHwLen }, { missingOptionVal },
                    { badOptionLen} };

            return Arrays.asList(input);
        }

        @Test(expected = MalformedPacketException.class)
        public void testDeserializeIncompletePacket()
                throws MalformedPacketException {
            ByteBuffer buff = ByteBuffer.wrap(this.input);
            DHCP dhcpPkt = new DHCP();
            dhcpPkt.deserialize(buff);
        }
    }
}
