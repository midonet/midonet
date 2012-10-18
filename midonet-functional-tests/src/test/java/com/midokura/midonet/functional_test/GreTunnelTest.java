/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.Ethernet;
import com.midokura.packets.IPv4;
import com.midokura.packets.UDP;
import com.midokura.packets.IPacket;
import com.midokura.packets.GRE;
import com.midokura.packets.MalformedPacketException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class GreTunnelTest extends BaseTunnelTest {
    private final static Logger log = LoggerFactory.getLogger(GreTunnelTest.class);

    @Override
    protected void setUpTunnelZone() throws Exception {
        // Create a gre tunnel zone
        log.info("Creating tunnel zone.");
        TunnelZone tunnelZone =
                apiClient.addGreTunnelZone().name("GreZone").create();

        // add this host to the capwap zone
        log.info("Adding this host to tunnel zone.");
        tunnelZone.addTunnelZoneHost().
                hostId(thisHostId).
                ipAddress(physTapLocalIp.toUnicastString()).
                create();

        log.info("Adding remote host to tunnelzone");
        tunnelZone.addTunnelZoneHost().
                hostId(remoteHostId).
                ipAddress(physTapRemoteIp.toUnicastString()).
                create();
    }

    @Override
    protected IPacket matchTunnelPacket(TapWrapper device,
                                      MAC fromMac, IntIPv4 fromIp,
                                      MAC toMac, IntIPv4 toIp)
                                throws MalformedPacketException {
        byte[] received = device.recv();
        assertNotNull(String.format("Expected packet on %s", device.getName()),
                      received);

        Ethernet eth = Ethernet.deserialize(received);
        log.info("got packet on " + device.getName() + ": " + eth.toString());

        assertEquals("source ethernet address",
            fromMac, eth.getSourceMACAddress());
        assertEquals("destination ethernet address",
            toMac, eth.getDestinationMACAddress());
        assertEquals("ethertype", IPv4.ETHERTYPE, eth.getEtherType());

        assertTrue("payload is IPv4", eth.getPayload() instanceof IPv4);
        IPv4 ipPkt = (IPv4) eth.getPayload();
        assertEquals("source ipv4 address",
            fromIp.addressAsInt(), ipPkt.getSourceAddress());
        assertEquals("destination ipv4 address",
            toIp.addressAsInt(), ipPkt.getDestinationAddress());

        assertTrue("payload is GRE", ipPkt.getPayload() instanceof GRE);
        GRE grePkt = (GRE) ipPkt.getPayload();
        return grePkt.getPayload();
    }

    // TODO(guillermo) use the GRE packet class to craft the packets.
    @Override
    protected byte[] buildEncapsulatedPacketForPort() {
        byte[] greFrame = new byte[]{
            (byte)0xbb, (byte)0xbb, (byte)0xbb, (byte)0xdd,
            (byte)0xdd, (byte)0xdd, (byte)0xaa, (byte)0xaa,
            (byte)0xaa, (byte)0xcc, (byte)0xcc, (byte)0xcc,
            (byte)0x08, (byte)0x00, (byte)0x45, (byte)0x00,
            (byte)0x00, (byte)0x51, (byte)0x18, (byte)0x29,
            (byte)0x00, (byte)0x00, (byte)0x40, (byte)0x2f,
            (byte)0x9e, (byte)0x67, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x02, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x01, (byte)0x20, (byte)0x00,
            (byte)0x65, (byte)0x58, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x02, (byte)0x22, (byte)0x22,
            (byte)0x22, (byte)0x11, (byte)0x11, (byte)0x11,
            (byte)0x33, (byte)0x33, (byte)0x33, (byte)0x44,
            (byte)0x44, (byte)0x44, (byte)0x08, (byte)0x00,
            (byte)0x45, (byte)0x00, (byte)0x00, (byte)0x27,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x40, (byte)0x11, (byte)0x2b, (byte)0x71,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x02,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x01,
            (byte)0x26, (byte)0x94, (byte)0x09, (byte)0x29,
            (byte)0x00, (byte)0x13, (byte)0x29, (byte)0xfd,
            (byte)0x54, (byte)0x68, (byte)0x65, (byte)0x20,
            (byte)0x50, (byte)0x61, (byte)0x79, (byte)0x6c,
            (byte)0x6f, (byte)0x61, (byte)0x64};
        writeOnPacket(greFrame, physTapLocalMac.getAddress(), 0);
        return greFrame;
    }

    @Override
    protected byte[] buildEncapsulatedPacketForPortSet() {
        byte[] greFrame = new byte[]{
            (byte)0xbb, (byte)0xbb, (byte)0xbb, (byte)0xdd,
            (byte)0xdd, (byte)0xdd, (byte)0xaa, (byte)0xaa,
            (byte)0xaa, (byte)0xcc, (byte)0xcc, (byte)0xcc,
            (byte)0x08, (byte)0x00, (byte)0x45, (byte)0x00,
            (byte)0x00, (byte)0x51, (byte)0x18, (byte)0x29,
            (byte)0x00, (byte)0x00, (byte)0x40, (byte)0x2f,
            (byte)0x9e, (byte)0x67, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x02, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x01, (byte)0x20, (byte)0x00,
            (byte)0x65, (byte)0x58, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x01, (byte)0x22, (byte)0x22,
            (byte)0x22, (byte)0x11, (byte)0x11, (byte)0x11,
            (byte)0x33, (byte)0x33, (byte)0x33, (byte)0x44,
            (byte)0x44, (byte)0x44, (byte)0x08, (byte)0x00,
            (byte)0x45, (byte)0x00, (byte)0x00, (byte)0x27,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x40, (byte)0x11, (byte)0x2b, (byte)0x71,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x02,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x01,
            (byte)0x26, (byte)0x94, (byte)0x09, (byte)0x29,
            (byte)0x00, (byte)0x13, (byte)0x29, (byte)0xfd,
            (byte)0x54, (byte)0x68, (byte)0x65, (byte)0x20,
            (byte)0x50, (byte)0x61, (byte)0x79, (byte)0x6c,
            (byte)0x6f, (byte)0x61, (byte)0x64};
        writeOnPacket(greFrame, physTapLocalMac.getAddress(), 0);
        return greFrame;
    }
}
