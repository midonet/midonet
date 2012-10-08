/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost;

public class GreTunnelTest extends BaseTunnelTest {
    private final static Logger log = LoggerFactory.getLogger(GreTunnelTest.class);

    protected void setUpTunnelZone() throws Exception {
        // Create a gre tunnel zone
        log.info("Creating tunnel zone.");
        TunnelZone tunnelZone =
                apiClient.addGreTunnelZone().name("GreZone").create();

        // add this host to the capwap zone
        log.info("Adding this host to tunnel zone.");
        /*
        tunnelZone.addTunnelZoneHost().
                hostId(thisHostId).
                ipAddress(physTapLocalIp.toUnicastString()).
                create();
                */
        dataClient.tunnelZonesAddMembership(tunnelZone.getId(),
            new GreTunnelZoneHost(thisHostId).
                setIp(physTapLocalIp));

        log.info("Adding remote host to tunnelzone");
        /*
        tunnelZone.addTunnelZoneHost().
                hostId(remoteHostId).
                ipAddress(physTapRemoteIp.toUnicastString()).
                create();
                */
        dataClient.tunnelZonesAddMembership(tunnelZone.getId(),
            new GreTunnelZoneHost(remoteHostId).
                setIp(physTapRemoteIp));
    }

    /*
    protected abstract IPacket encapsulatePacket(IPacket payload);
    */
}
