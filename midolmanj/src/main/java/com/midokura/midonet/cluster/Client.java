/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster;

import java.util.UUID;

import com.midokura.midonet.cluster.client.TunnelZones;
import com.midokura.midonet.cluster.client.BridgeBuilder;
import com.midokura.midonet.cluster.client.ChainBuilder;
import com.midokura.midonet.cluster.client.HostBuilder;
import com.midokura.midonet.cluster.client.PortBuilder;
import com.midokura.midonet.cluster.client.RouterBuilder;

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
}
