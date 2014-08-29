/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.sdn.flows;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.midonet.odp.FlowMatches.tcpFlow;

public class WildcardMatchTest {

    @Test
    public void testDefaultCtor() {
        WildcardMatch wmatch = new WildcardMatch();
        assertThat(wmatch.getUsedFields(), hasSize(0));
    }

    @Test
    public void testSetDlDest() {
        WildcardMatch wmatch = new WildcardMatch();
        byte[] dlDest = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlDestStr = "0a:0b:0c:0d:0e:0f";
        wmatch.setEthernetDestination(dlDestStr);
        assertArrayEquals(dlDest, wmatch.getEthernetDestination().getAddress());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.EthernetDestination));
        assertEquals(wmatch.highestLayerSeen(), 2);
    }

    @Test
    public void testSetDlSource() {
        WildcardMatch wmatch = new WildcardMatch();
        byte[] dlSource = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlSourceStr = "0a:0b:0c:0d:0e:0f";
        wmatch.setEthernetSource(dlSourceStr);
        assertArrayEquals(dlSource, wmatch.getEthernetSource().getAddress());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.EthernetSource));
        assertEquals(wmatch.highestLayerSeen(), 2);
    }

    @Test
    public void testSetDlType() {
        WildcardMatch wmatch = new WildcardMatch();
        Short dlType = 0x11ee;
        wmatch.setEtherType(dlType);
        assertEquals(dlType, wmatch.getEtherType());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.EtherType));
        assertEquals(wmatch.highestLayerSeen(), 2);
    }

    @Test
    public void testSetInputPort() {
        WildcardMatch wmatch = new WildcardMatch();
        Short inPort = 0x11ee;
        wmatch.setInputPortNumber(inPort);
        assertThat(wmatch.getInputPortNumber(), is(inPort));
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.InputPortNumber));
    }

    @Test
    public void testSetNwProto() {
        WildcardMatch wmatch = new WildcardMatch();
        Byte nwProto = 0x11;
        wmatch.setNetworkProtocol(nwProto);
        assertEquals(nwProto, wmatch.getNetworkProtocol());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.NetworkProtocol));
    }

    @Test
    public void testSetIcmpIdentifier() {
        WildcardMatch wmatch = new WildcardMatch();
        Assert.assertFalse(wmatch.userspaceFieldsSeen());
        Short icmpId = 0x25;
        wmatch.setIcmpIdentifier(icmpId);
        assertEquals(icmpId, wmatch.getIcmpIdentifier());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.IcmpId));
        Assert.assertTrue(wmatch.userspaceFieldsSeen());
    }

    @Test
    public void testSetTpDest() {
        WildcardMatch wmatch = new WildcardMatch();
        Integer tpDest = 0x11ee;
        wmatch.setTransportDestination(tpDest);
        assertEquals(tpDest, wmatch.getTransportDestination());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.TransportDestination));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetTpDestHigh() {
        WildcardMatch wmatch = new WildcardMatch();
        Integer tpDest = 0xA8CA;
        wmatch.setTransportDestination(tpDest);
        assertEquals(tpDest, wmatch.getTransportDestination());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.TransportDestination));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetTpSource() {
        WildcardMatch wmatch = new WildcardMatch();
        Integer tpSource = 0x11ee;
        wmatch.setTransportSource(tpSource);
        assertEquals(tpSource, wmatch.getTransportSource());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.TransportSource));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetTpSourceHigh() {
        WildcardMatch wmatch = new WildcardMatch();
        Integer tpSource = 0xA8CA;
        wmatch.setTransportSource(tpSource);
        assertEquals(tpSource, wmatch.getTransportSource());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.TransportSource));
        assertEquals(wmatch.highestLayerSeen(), 4);
    }

    @Test
    public void testSetNwDst_networkRange() {
        WildcardMatch wmatch = new WildcardMatch();
        int nwDest = 0x12345678;
        wmatch.setNetworkDestination(IPv4Addr.fromInt(nwDest));
        IPAddr ipDst = wmatch.getNetworkDestinationIP();
        assertThat(ipDst, notNullValue());
        assertEquals(ipDst, IPv4Addr.fromInt(nwDest));
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.NetworkDestination));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testSetNwDst_unicastAddress() {
        WildcardMatch wmatch = new WildcardMatch();
        int nwDest = 0x12345678;
        wmatch.setNetworkDestination(IPv4Addr.fromInt(nwDest));
        IPAddr ipDst = wmatch.getNetworkDestinationIP();
        assertThat(ipDst, notNullValue());
        assertEquals(ipDst, IPv4Addr.fromInt(nwDest));
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.NetworkDestination));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testSetNwSrc_networkRange() {
        WildcardMatch wmatch = new WildcardMatch();
        int nwSource = 0x12345678;
        wmatch.setNetworkSource(IPv4Addr.fromInt(nwSource));
        IPAddr ipSrc = wmatch.getNetworkSourceIP();
        assertThat(ipSrc, notNullValue());
        assertEquals(ipSrc, IPv4Addr.fromInt(nwSource));
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.NetworkSource));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testSetNwSrc_unicastAddress() {
        WildcardMatch wmatch = new WildcardMatch();
        int nwSource = 0x12345678;
        wmatch.setNetworkSource(IPv4Addr.fromInt(nwSource));
        IPAddr ipSrc = wmatch.getNetworkSourceIP();
        assertThat(ipSrc, notNullValue());
        assertEquals(ipSrc, IPv4Addr.fromInt(nwSource));
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                   contains(WildcardMatch.Field.NetworkSource));
        assertEquals(wmatch.highestLayerSeen(), 3);
    }

    @Test
    public void testEqualityRelationByProjection() {

        WildcardMatch wildcard =
            WildcardMatch.fromFlowMatch(
                tcpFlow("ae:b3:77:8c:a1:48", "33:33:00:00:00:16",
                        "192.168.100.1", "192.168.100.2",
                        8096, 1025, 0));

        WildcardMatch projection = wildcard.project(EnumSet.of(
                WildcardMatch.Field.EthernetSource,
                WildcardMatch.Field.EthernetDestination));

        assertThat("A wildcard should not match a projection smaller than it",
                   wildcard, not(equalTo(projection)));

        assertThat("A project should be equal to a wildcard bigger than it.",
                   projection, equalTo(wildcard));
    }

    @Test
    public void testFindableInMap() {
        WildcardMatch wildcard =
            WildcardMatch.fromFlowMatch(
                tcpFlow("ae:b3:77:8c:a1:48", "33:33:00:00:00:16",
                        "192.168.100.1", "192.168.100.2",
                        8096, 1025, 0));

        WildcardMatch projection = wildcard.project(EnumSet.of(
            WildcardMatch.Field.EthernetSource,
            WildcardMatch.Field.EthernetDestination));

        // make a simple wildcard that is a copy of the projection
        WildcardMatch copy = new WildcardMatch();
        copy.setEthernetDestination(projection.getEthernetDestination());
        copy.setEthernetSource(projection.getEthernetSource());

        Map<WildcardMatch, Boolean> map = new HashMap<WildcardMatch, Boolean>();
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
        FlowMatch fm = FlowMatches.tcpFlow(
            "02:aa:dd:dd:aa:01", "02:bb:ee:ee:ff:01",
            "192.168.100.2", "192.168.100.3",
            40000, 50000, 0);
        WildcardMatch wcm = WildcardMatch.fromFlowMatch(fm);
        assertThat(wcm.getTransportSource(),
                   equalTo(40000));
        assertThat(wcm.getTransportDestination(),
                   equalTo(50000));

    }

    @Test
    public void testFieldSetEquality() {
        WildcardMatch m1 = new WildcardMatch();
        WildcardMatch m2 = new WildcardMatch();

        m1.setInputPortNumber((short) 1);
        m1.setEthernetSource(MAC.fromString("aa:ff:bb:dd:ee:dd"));
        m1.setEthernetDestination(MAC.fromString("bb:ff:bb:ff:ff:dd"));
        m1.setEtherType(ARP.ETHERTYPE);
        m1.setNetworkSource(IPv4Addr.fromString("10.0.0.1"));
        m1.setNetworkDestination(IPv4Addr.fromString("10.0.0.2"));

        m2.setInputPortNumber((short) 1);
        m2.setEthernetSource(MAC.fromString("ee:ee:bb:dd:ee:dd"));
        m2.setEthernetDestination(MAC.fromString("ee:ff:ee:ff:ff:dd"));
        m2.setEtherType(IPv4.ETHERTYPE);
        m2.setNetworkSource(IPv4Addr.fromString("10.0.0.1"));

        Assert.assertNotSame(m1.getUsedFields(), m2.getUsedFields());
        Map<Set<WildcardMatch.Field>, Object> m = new
            ConcurrentHashMap<Set<WildcardMatch.Field>, Object>(10, 10, 1);
        m.put(m1.getUsedFields(), new Object());
        Assert.assertFalse(m.containsKey(m2.getUsedFields()));

        m2.setNetworkDestination(IPv4Addr.fromString("10.0.0.2"));

        assertEquals(m1.getUsedFields(), m2.getUsedFields());
        Assert.assertTrue(m.containsKey(m2.getUsedFields()));
    }

    @Test
    public void testHighestLayerUsed() {
        WildcardMatch m = new WildcardMatch();
        assertEquals(m.highestLayerSeen(), 0);
        m.getEthernetSource();
        assertEquals(m.highestLayerSeen(), 2);
        m.getTransportDestination();
        assertEquals(m.highestLayerSeen(), 4);
        m.getNetworkSourceIP();
        m.getNetworkTTL();
        assertEquals(m.highestLayerSeen(), 4);
    }

}
