/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.sdn.flows;

import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.is;

import org.midonet.packets.IntIPv4;
import org.midonet.packets.IPv4Addr;


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
        wmatch.setDataLayerDestination(dlDestStr);
        Assert.assertArrayEquals(dlDest, wmatch.getDataLayerDestination());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.EthernetDestination));
    }

    @Test
    public void testSetDlSource() {
        WildcardMatch wmatch = new WildcardMatch();
        byte[] dlSource = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlSourceStr = "0a:0b:0c:0d:0e:0f";
        wmatch.setDataLayerSource(dlSourceStr);
        Assert.assertArrayEquals(dlSource, wmatch.getDataLayerSource());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.EthernetSource));
    }

    @Test
    public void testSetDlType() {
        WildcardMatch wmatch = new WildcardMatch();
        short dlType = 0x11ee;
        wmatch.setDataLayerType(dlType);
        Assert.assertEquals(dlType, wmatch.getDataLayerType());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.EtherType));
    }

    @Test
    public void testSetInputPort() {
        WildcardMatch wmatch = new WildcardMatch();
        short inPort = 0x11ee;
        wmatch.setInputPort(inPort);
        assertThat(wmatch.getInputPort(), is(inPort));
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.InputPortNumber));
    }

    @Test
    public void testSetNwProto() {
        WildcardMatch wmatch = new WildcardMatch();
        byte nwProto = 0x11;
        wmatch.setNetworkProtocol(nwProto);
        Assert.assertEquals(nwProto, wmatch.getNetworkProtocol());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.NetworkProtocol));
    }

    @Test
    public void testSetTpDest() {
        WildcardMatch wmatch = new WildcardMatch();
        int tpDest = 0x11ee;
        wmatch.setTransportDestination(tpDest);
        Assert.assertEquals(tpDest, wmatch.getTransportDestination());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.TransportDestination));
    }

    @Test
    public void testSetTpDestHigh() {
        WildcardMatch wmatch = new WildcardMatch();
        int tpDest = 0xA8CA;
        wmatch.setTransportDestination(tpDest);
        Assert.assertEquals(tpDest, wmatch.getTransportDestination());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                contains(WildcardMatch.Field.TransportDestination));
    }

    @Test
    public void testSetTpSource() {
        WildcardMatch wmatch = new WildcardMatch();
        int tpSource = 0x11ee;
        wmatch.setTransportSource(tpSource);
        Assert.assertEquals(tpSource, wmatch.getTransportSource());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.TransportSource));
    }

    @Test
    public void testSetTpSourceHigh() {
        WildcardMatch wmatch = new WildcardMatch();
        int tpSource = 0xA8CA;
        wmatch.setTransportSource(tpSource);
        Assert.assertEquals(tpSource, wmatch.getTransportSource());
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                contains(WildcardMatch.Field.TransportSource));
    }

    @Test
    public void testSetNwDst_networkRange() {
        int len = 25;
        int expectedLen = 32;
        WildcardMatch wmatch = new WildcardMatch();
        int nwDest = 0x12345678;
        wmatch.setNetworkDestination(new IPv4Addr().setIntAddress(nwDest));
        IntIPv4 ipDst = wmatch.getNetworkDestinationIPv4();
        assertThat(ipDst, notNullValue());
        if (null != ipDst) {
            assertThat(ipDst.getMaskLength(), is(expectedLen));
            assertThat(ipDst.addressAsInt(), is(nwDest));
        }
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.NetworkDestination));
    }

    @Test
    public void testSetNwDst_unicastAddress() {
        int len = 32;
        WildcardMatch wmatch = new WildcardMatch();
        int nwDest = 0x12345678;
        wmatch.setNetworkDestination(new IPv4Addr().setIntAddress(nwDest));
        IntIPv4 ipDst = wmatch.getNetworkDestinationIPv4();
        assertThat(ipDst, notNullValue());
        if (null != ipDst) {
            assertThat(ipDst.getMaskLength(), is(len));
            assertThat(ipDst.addressAsInt(), is(nwDest));
        }
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                contains(WildcardMatch.Field.NetworkDestination));
    }

    @Test
    public void testSetNwSrc_networkRange() {
        int len = 25;
        int expectedLen = 32;
        WildcardMatch wmatch = new WildcardMatch();
        int nwSource = 0x12345678;
        wmatch.setNetworkSource(new IPv4Addr().setIntAddress(nwSource));
        IntIPv4 ipSrc = wmatch.getNetworkSourceIPv4();
        assertThat(ipSrc, notNullValue());
        if (null != ipSrc) {
            assertThat(ipSrc.getMaskLength(), is(expectedLen));
            assertThat(ipSrc.addressAsInt(), is(nwSource));
        }
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
            contains(WildcardMatch.Field.NetworkSource));
    }

    @Test
    public void testSetNwSrc_unicastAddress() {
        int len = 32;
        WildcardMatch wmatch = new WildcardMatch();
        int nwSource = 0x12345678;
        wmatch.setNetworkSource(new IPv4Addr().setIntAddress(nwSource));
        IntIPv4 ipSrc = wmatch.getNetworkSourceIPv4();
        assertThat(ipSrc, notNullValue());
        if (null != ipSrc) {
            assertThat(ipSrc.getMaskLength(), is(len));
            assertThat(ipSrc.addressAsInt(), is(nwSource));
        }
        assertThat(wmatch.getUsedFields(), hasSize(1));
        assertThat(wmatch.getUsedFields(),
                contains(WildcardMatch.Field.NetworkSource));
    }
}
