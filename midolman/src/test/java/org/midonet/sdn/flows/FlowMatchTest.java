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

package org.midonet.sdn.flows;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMatches;
import org.midonet.packets.ARP;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.midonet.odp.FlowMatches.tcpFlow;

public class FlowMatchTest {

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
        Assert.assertFalse(wmatch.userspaceFieldsSeen());
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
        IPAddr ipDst = wmatch.getNetworkDstIP();
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
        IPAddr ipDst = wmatch.getNetworkDstIP();
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
        IPAddr ipSrc = wmatch.getNetworkSrcIP();
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
        IPAddr ipSrc = wmatch.getNetworkSrcIP();
        assertThat(ipSrc, notNullValue());
        assertEquals(ipSrc, IPv4Addr.fromInt(nwSource));
        assertThat(Long.bitCount(wmatch.getUsedFields()), is(1));
        assertTrue(wmatch.isUsed(FlowMatch.Field.NetworkSrc));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testEqualityRelationByProjection() {

        FlowMatch wildcard =
                tcpFlow("ae:b3:77:8c:a1:48", "33:33:00:00:00:16",
                        "192.168.100.1", "192.168.100.2",
                        8096, 1025, 0);

        FlowMatch projection = ProjectedFlowMatch.project(wildcard,
            (1L << FlowMatch.Field.EthSrc.ordinal()) |
            (1L << FlowMatch.Field.EthDst.ordinal()));

        assertThat("A wildcard should not match a projection smaller than it",
                   wildcard, not(equalTo(projection)));

        assertThat("A project should be equal to a wildcard bigger than it.",
                   projection, equalTo(wildcard));
    }

    @Test
    public void testFindableInMap() {
        FlowMatch wildcard =
                tcpFlow("ae:b3:77:8c:a1:48", "33:33:00:00:00:16",
                        "192.168.100.1", "192.168.100.2",
                        8096, 1025, 0);

        FlowMatch projection = ProjectedFlowMatch.project(wildcard,
            (1L << FlowMatch.Field.EthSrc.ordinal()) |
            (1L << FlowMatch.Field.EthDst.ordinal()));

        // make a simple wildcard that is a copy of the projection
        FlowMatch copy = new FlowMatch();
        copy.setEthDst(projection.getEthDst());
        copy.setEthSrc(projection.getEthSrc());

        Map<FlowMatch, Boolean> map = new HashMap<FlowMatch, Boolean>();
        map.put(copy, Boolean.TRUE);

        assertThat(
            "We should be able to retrieve a wildcard flow by projection",
            map.get(projection), is(notNullValue()));

        assertThat(
            "We should be able to retrieve a wildcard flow by projection",
            map.get(projection), is(true));
    }

    @Test
    public void testFromFlowMatch() {
        org.midonet.odp.FlowMatch fm = FlowMatches.tcpFlow(
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
        m1.setEtherType(ARP.ETHERTYPE);
        m1.setNetworkSrc(IPv4Addr.fromString("10.0.0.1"));
        m1.setNetworkDst(IPv4Addr.fromString("10.0.0.2"));

        m2.setInputPortNumber((short) 1);
        m2.setEthSrc(MAC.fromString("ee:ee:bb:dd:ee:dd"));
        m2.setEthDst(MAC.fromString("ee:ff:ee:ff:ff:dd"));
        m2.setEtherType(IPv4.ETHERTYPE);
        m2.setNetworkSrc(IPv4Addr.fromString("10.0.0.1"));

        Assert.assertNotSame(m1.getUsedFields(), m2.getUsedFields());
        Map<Long, Object> m = new ConcurrentHashMap<>(10, 10, 1);
        m.put(m1.getUsedFields(), new Object());
        Assert.assertFalse(m.containsKey(m2.getUsedFields()));

        m2.setNetworkDst(IPv4Addr.fromString("10.0.0.2"));

        assertEquals(m1.getUsedFields(), m2.getUsedFields());
        assertTrue(m.containsKey(m2.getUsedFields()));
    }

    @Test
    public void testHighestLayerUsed() {
        FlowMatch m = new FlowMatch();
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
