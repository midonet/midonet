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

package org.midonet.odp.flows;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMatches;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.midonet.odp.flows.FlowKeys.arp;
import static org.midonet.odp.flows.FlowKeys.tcp;
import static org.midonet.odp.flows.FlowKeys.icmp;
import static org.midonet.odp.flows.FlowKeys.icmpEcho;
import static org.midonet.odp.flows.FlowKeys.icmpError;

public class FlowMatchTest {

    private ArrayList<FlowKey> supported = new ArrayList<>();
    private ArrayList<FlowKey> unsupported = new ArrayList<>();
    private ArrayList<FlowKey> tmp = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        supported.add(icmp(ICMP.TYPE_ECHO_REQUEST, ICMP.CODE_NONE));
        supported.add(arp(null, null, (short) 0, 0, 0));
        supported.add(tcp(0, 0));
        unsupported.add(icmpEcho(ICMP.TYPE_ECHO_REQUEST, ICMP.CODE_NONE, (short)0));
        unsupported.add(icmpError(ICMP.TYPE_ECHO_REQUEST, ICMP.CODE_NONE, null));
    }

    @After
    public void tearDown() {
        supported.clear();
        unsupported.clear();
        tmp = null;
    }

    @Test
    public void testAddKey() {
        FlowMatch m = new FlowMatch();
        assertFalse(m.hasUserspaceOnlyFields());
        for (FlowKey key : supported) {
            m.addKey(key);
        }
        assertFalse(m.hasUserspaceOnlyFields());
        m.addKey(unsupported.get(0));
        assertTrue(m.hasUserspaceOnlyFields());
        assertEquals(supported.size() + 1, m.getKeys().size());
    }

    @Test
    public void testConstructWithKeys() {
        tmp.addAll(supported);
        FlowMatch m = new FlowMatch(tmp);
        assertFalse(m.hasUserspaceOnlyFields());
        assertEquals(supported.size(), m.getKeys().size());
        m.addKey(unsupported.get(0));
        assertTrue(m.hasUserspaceOnlyFields());
        assertEquals(supported.size() + 1, m.getKeys().size());

        tmp = new ArrayList<>();
        tmp.addAll(supported);
        tmp.addAll(unsupported);
        m = new FlowMatch(tmp);
        assertTrue(m.hasUserspaceOnlyFields());
        assertEquals(supported.size() + unsupported.size(), m.getKeys().size());
    }

    @Test
    public void testConstructFromEthernetWithICMPReplacement() {
        MAC srcMac = MAC.fromString("aa:bb:cc:dd:ee:ff");
        MAC dstMac = MAC.fromString("ff:ee:dd:cc:bb:aa");
        IPv4Addr srcIp = IPv4Addr.fromString("10.0.0.1");
        IPv4Addr dstIp = IPv4Addr.fromString("10.0.0.2");
        ICMP icmp1 = new ICMP();
        ICMP icmp2 = new ICMP();
        ICMP icmp3 = new ICMP();
        ICMP icmp4 = new ICMP();
        icmp1.setEchoRequest((short)9507, (short)10, "hello".getBytes());
        icmp2.setEchoRequest((short)9507, (short)10, "hello".getBytes());
        icmp3.setEchoRequest((short)9508, (short)11, "hello".getBytes());
        icmp4.setEchoRequest((short)9508, (short)12, "hello".getBytes());
        Ethernet eth1 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp1);
        Ethernet eth2 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp2);
        Ethernet eth3 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp3);
        Ethernet eth4 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp4);
        FlowMatch m1 = new FlowMatch();
        FlowMatch m2 = new FlowMatch();
        FlowMatch m3 = new FlowMatch();
        FlowMatch m4 = new FlowMatch();
        m1.addKey(FlowKeys.makeIcmpFlowKey(icmp1));
        m2.addKey(FlowKeys.makeIcmpFlowKey(icmp1));
        m3.addKey(FlowKeys.makeIcmpFlowKey(icmp1));
        m4.addKey(FlowKeys.makeIcmpFlowKey(icmp1));

        assertEquals(m1, m2);
        assertNotSame(m1, m3);
        assertNotSame(m1, m4);
        assertNotSame(m3, m4);

        assertEquals(m1, m2);
        assertNotSame(m1, m3);
        assertNotSame(m1, m4);
        assertTrue(m1.hasUserspaceOnlyFields());
        assertTrue(m2.hasUserspaceOnlyFields());
        assertTrue(m3.hasUserspaceOnlyFields());
        assertTrue(m4.hasUserspaceOnlyFields());
    }

    private Ethernet makeFrame(MAC srcMac, MAC dstMac,
                               IPv4Addr srcIp, IPv4Addr dstIp,
                               ICMP payload) {
        IPv4 ipv4 = new IPv4();
        ipv4.setSourceAddress(srcIp);
        ipv4.setDestinationAddress(dstIp);
        ipv4.setProtocol(ICMP.PROTOCOL_NUMBER);
        ipv4.setPayload(payload);
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress(srcMac);
        eth.setDestinationMACAddress(dstMac);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ipv4);
        return eth;
    }

    /*
     * Guarantee that those keys that don't generate the enriched FlowKeyICMPs
     * with user only fields still work. See MN-900.
     */
    @Test
    public void testNonUserspaceOnlyIcmps() {
        MAC srcMac = MAC.fromString("aa:bb:cc:dd:ee:ff");
        MAC dstMac = MAC.fromString("ff:ee:dd:cc:bb:aa");
        IPv4Addr srcIp = IPv4Addr.fromString("10.0.0.1");
        IPv4Addr dstIp = IPv4Addr.fromString("10.0.0.2");
        IPv4 ipv4 = new IPv4();
        ipv4.setSourceAddress(srcIp);
        ipv4.setDestinationAddress(dstIp);
        ipv4.setProtocol(ICMP.PROTOCOL_NUMBER);
        ICMP icmp = new ICMP();
        icmp.setType(ICMP.TYPE_ROUTER_SOLICITATION, ICMP.CODE_NONE, null);
        Ethernet eth = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp);
        FlowMatch match = FlowMatches.fromEthernetPacket(eth);
        assertEquals(4, match.getKeys().size());
        assertTrue(match.getKeys().get(0) instanceof FlowKeyEthernet);
        assertTrue(match.getKeys().get(1) instanceof FlowKeyEtherType);
        assertTrue(match.getKeys().get(2) instanceof FlowKeyIPv4);
        assertTrue(match.getKeys().get(3) instanceof FlowKeyICMP);
        FlowKeyICMP fkIcmp = (FlowKeyICMP) match.getKeys().get(3);
        assertEquals(fkIcmp.icmp_type, ICMP.TYPE_ROUTER_SOLICITATION);
        assertEquals(fkIcmp.icmp_code, ICMP.CODE_NONE);
    }

        @Test
    public void testDefaultCtor() {
        FlowMatch wmatch = new FlowMatch();
        assertThat(wmatch.getUsedFields(), is(0L));
    }

    @Test
    public void testSetDlDest() {
        FlowMatch wmatch = new FlowMatch();
        byte[] dlDest = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlDestStr = "0a:0b:0c:0d:0e:0f";
        wmatch.setEthDst(dlDestStr);
        assertArrayEquals(dlDest, wmatch.getEthDst().getAddress());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.EthDst));
        assertEquals(wmatch.highestLayerSeen(), 2);
    }

    @Test
    public void testSetDlSource() {
        FlowMatch wmatch = new FlowMatch();
        byte[] dlSource = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlSourceStr = "0a:0b:0c:0d:0e:0f";
        wmatch.setEthSrc(dlSourceStr);
        assertArrayEquals(dlSource, wmatch.getEthSrc().getAddress());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.EthSrc));
        assertEquals(wmatch.highestLayerSeen(), 2);
    }

    @Test
    public void testSetDlType() {
        FlowMatch wmatch = new FlowMatch();
        short dlType = 0x11ee;
        wmatch.setEtherType(dlType);
        assertEquals(dlType, wmatch.getEtherType());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.EtherType));
        assertEquals(wmatch.highestLayerSeen(), 2);
    }

    @Test
    public void testSetInputPort() {
        FlowMatch wmatch = new FlowMatch();
        Integer inPort = 0x11ee;
        wmatch.setInputPortNumber(inPort);
        assertThat(wmatch.getInputPortNumber(), is(inPort));
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.InputPortNumber));
    }

    @Test
    public void testSetNwProto() {
        FlowMatch wmatch = new FlowMatch();
        byte nwProto = 0x11;
        wmatch.setNetworkProto(nwProto);
        assertEquals(nwProto, wmatch.getNetworkProto());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.NetworkProto));
    }

    @Test
    public void testSetIcmpIdentifier() {
        FlowMatch wmatch = new FlowMatch();
        org.junit.Assert.assertFalse(wmatch.userspaceFieldsSeen());
        short icmpId = 0x25;
        wmatch.setIcmpIdentifier(icmpId);
        assertEquals(icmpId, wmatch.getIcmpIdentifier());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.IcmpId));
        assertTrue(wmatch.userspaceFieldsSeen());
    }

    @Test
    public void testSetTpDest() {
        FlowMatch wmatch = new FlowMatch();
        int tpDest = 0x11ee;
        wmatch.setDstPort(tpDest);
        assertEquals(tpDest, wmatch.getDstPort());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.DstPort));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetTpDestHigh() {
        FlowMatch wmatch = new FlowMatch();
        int tpDest = 0xA8CA;
        wmatch.setDstPort(tpDest);
        assertEquals(tpDest, wmatch.getDstPort());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.DstPort));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetTpSource() {
        FlowMatch wmatch = new FlowMatch();
        int tpSource = 0x11ee;
        wmatch.setSrcPort(tpSource);
        assertEquals(tpSource, wmatch.getSrcPort());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.SrcPort));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetTpSourceHigh() {
        FlowMatch wmatch = new FlowMatch();
        int tpSource = 0xA8CA;
        wmatch.setSrcPort(tpSource);
        assertEquals(tpSource, wmatch.getSrcPort());
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.SrcPort));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetNwDst_networkRange() {
        FlowMatch wmatch = new FlowMatch();
        int nwDest = 0x12345678;
        wmatch.setNetworkDst(IPv4Addr.fromInt(nwDest));
        org.midonet.packets.IPAddr ipDst = wmatch.getNetworkDstIP();
        assertThat(ipDst, notNullValue());
        assertEquals(ipDst, IPv4Addr.fromInt(nwDest));
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.NetworkDst));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testSetNwDst_unicastAddress() {
        FlowMatch wmatch = new FlowMatch();
        int nwDest = 0x12345678;
        wmatch.setNetworkDst(IPv4Addr.fromInt(nwDest));
        org.midonet.packets.IPAddr ipDst = wmatch.getNetworkDstIP();
        assertThat(ipDst, notNullValue());
        assertEquals(ipDst, IPv4Addr.fromInt(nwDest));
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.NetworkDst));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testSetNwSrc_networkRange() {
        FlowMatch wmatch = new FlowMatch();
        int nwSource = 0x12345678;
        wmatch.setNetworkSrc(IPv4Addr.fromInt(nwSource));
        org.midonet.packets.IPAddr ipSrc = wmatch.getNetworkSrcIP();
        assertThat(ipSrc, notNullValue());
        assertEquals(ipSrc, IPv4Addr.fromInt(nwSource));
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.NetworkSrc));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testSetNwSrc_unicastAddress() {
        FlowMatch wmatch = new FlowMatch();
        int nwSource = 0x12345678;
        wmatch.setNetworkSrc(IPv4Addr.fromInt(nwSource));
        org.midonet.packets.IPAddr ipSrc = wmatch.getNetworkSrcIP();
        assertThat(ipSrc, notNullValue());
        assertEquals(ipSrc, IPv4Addr.fromInt(nwSource));
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.NetworkSrc));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testFromFlowMatch() {
        FlowMatch fm = FlowMatches.tcpFlow(
            "02:aa:dd:dd:aa:01", "02:bb:ee:ee:ff:01",
            "192.168.100.2", "192.168.100.3",
            40000, 50000, 0);
        assertThat(fm.getSrcPort(),
                   equalTo(40000));
        assertThat(fm.getDstPort(),
                   equalTo(50000));

    }

    @Test
    public void testFieldSetEquality() {
        FlowMatch m1 = new FlowMatch();
        FlowMatch m2 = new FlowMatch();

        m1.setInputPortNumber((short) 1);
        m1.setEthSrc(MAC.fromString("aa:ff:bb:dd:ee:dd"));
        m1.setEthDst(MAC.fromString("bb:ff:bb:ff:ff:dd"));
        m1.setEtherType(org.midonet.packets.ARP.ETHERTYPE);
        m1.setNetworkSrc(IPv4Addr.fromString("10.0.0.1"));
        m1.setNetworkDst(IPv4Addr.fromString("10.0.0.2"));

        m2.setInputPortNumber((short) 1);
        m2.setEthSrc(MAC.fromString("ee:ee:bb:dd:ee:dd"));
        m2.setEthDst(MAC.fromString("ee:ff:ee:ff:ff:dd"));
        m2.setEtherType(IPv4.ETHERTYPE);
        m2.setNetworkSrc(IPv4Addr.fromString("10.0.0.1"));

        org.junit.Assert.assertNotSame(m1.getUsedFields(), m2.getUsedFields());
        java.util.Map<Long, Object>
            m = new java.util.concurrent.ConcurrentHashMap<>(10, 10, 1);
        m.put(m1.getUsedFields(), new Object());
        org.junit.Assert.assertFalse(m.containsKey(m2.getUsedFields()));

        m2.setNetworkDst(IPv4Addr.fromString("10.0.0.2"));

        assertEquals(m1.getUsedFields(), m2.getUsedFields());
        assertTrue(m.containsKey(m2.getUsedFields()));
    }

    @Test
    public void testHighestLayerUsed() {
        FlowMatch m = new FlowMatch();
        m.setEthSrc(MAC.random());
        m.setDstPort(1);
        m.setNetworkSrc(IPv4Addr.random());
        m.setNetworkTTL((byte)1);

        assertEquals(m.highestLayerSeen(), 0);
        m.getEthSrc();
        assertEquals(m.highestLayerSeen(), 2);
        m.getDstPort();
        assertEquals(m.highestLayerSeen(), 4);
        m.getNetworkSrcIP();
        m.getNetworkTTL();
        assertEquals(m.highestLayerSeen(), 4);
    }
}
