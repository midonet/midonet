/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster;

import java.util.UUID;

import org.midonet.cluster.client.BGPListBuilder;
import org.midonet.cluster.client.BridgeBuilder;
import org.midonet.cluster.client.ChainBuilder;
import org.midonet.cluster.client.HostBuilder;
import org.midonet.cluster.client.PortBuilder;
import org.midonet.cluster.client.PortSetBuilder;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.client.TraceConditionsBuilder;
import org.midonet.cluster.client.TunnelZones;

public interface Client {

    enum PortType {
        InteriorBridge, ExteriorBridge, InteriorRouter, ExteriorRouter
    }

    void getBridge(UUID bridgeID, BridgeBuilder builder);

    void getRouter(UUID routerID, RouterBuilder builder);

    void getChain(UUID chainID, ChainBuilder builder);

    void getPort(UUID portID, PortBuilder builder);

    void getHost(UUID hostIdentifier, HostBuilder builder);

    void getTunnelZones(UUID uuid, TunnelZones.BuildersProvider builders);

    void getPortSet(UUID uuid, PortSetBuilder builder);

    void getTraceConditions(TraceConditionsBuilder builder);

    void subscribeBgp(UUID portID, BGPListBuilder builder);
}
