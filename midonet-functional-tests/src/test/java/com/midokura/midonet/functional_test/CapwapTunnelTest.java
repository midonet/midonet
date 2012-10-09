/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import com.midokura.packets.IPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.zones.CapwapTunnelZoneHost;
import com.midokura.midonet.cluster.data.zones.CapwapTunnelZone;

public class CapwapTunnelTest extends BaseTunnelTest {
    private final static Logger log = LoggerFactory.getLogger(CapwapTunnelTest.class);

    protected void setUpTunnelZone() throws Exception {
        // Create a capwap tunnel zone
        log.info("Creating tunnel zone.");
        TunnelZone tunnelZone =
                apiClient.addCapwapTunnelZone().name("CapwapZone").create();

        // add this host to the capwap zone
        log.info("Adding this host to tunnel zone.");
        /*
        tunnelZone.addTunnelZoneHost().
                hostId(thisHostId).
                ipAddress(physTapLocalIp.toUnicastString()).
                create();
                */
        dataClient.tunnelZonesAddMembership(tunnelZone.getId(),
            new CapwapTunnelZoneHost(thisHostId).
                setIp(physTapLocalIp));

        log.info("Adding remote host to tunnelzone");
        /*
        tunnelZone.addTunnelZoneHost().
                hostId(remoteHostId).
                ipAddress(physTapRemoteIp.toUnicastString()).
                create();
                */
        dataClient.tunnelZonesAddMembership(tunnelZone.getId(),
            new CapwapTunnelZoneHost(remoteHostId).
                setIp(physTapRemoteIp));
    }

    @Override
    protected byte[] buildEncapsulatedPacket() {
        byte[] capwapFrame = {
            (byte)0xbb, (byte)0xbb, (byte)0xbb, (byte)0xdd,
            (byte)0xdd, (byte)0xdd, (byte)0xaa, (byte)0xaa,
            (byte)0xaa, (byte)0xcc, (byte)0xcc, (byte)0xcc,
            (byte)0x08, (byte)0x00, (byte)0x45, (byte)0x00,
            (byte)0x00, (byte)0x65, (byte)0x18, (byte)0x25,
            (byte)0x00, (byte)0x00, (byte)0x40, (byte)0x11,
            (byte)0x9e, (byte)0x75, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x02, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x01, (byte)0xe6, (byte)0x02,
            (byte)0xe6, (byte)0x01, (byte)0x00, (byte)0x51,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x28,
            (byte)0x3c, (byte)0x20, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x0b, (byte)0x80,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
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
        writeOnPacket(capwapFrame, physTapLocalMac.getAddress(), 0);
        return capwapFrame;
    }
}

