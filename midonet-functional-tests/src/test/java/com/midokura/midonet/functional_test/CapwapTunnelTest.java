/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

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

    /*
    protected abstract IPacket encapsulatePacket(IPacket payload);
    */
}
