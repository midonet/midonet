/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster;

import java.util.UUID;

import org.midonet.cluster.client.BGPListBuilder;
import org.midonet.cluster.client.BridgeBuilder;
import org.midonet.cluster.client.ChainBuilder;
import org.midonet.cluster.client.HealthMonitorBuilder;
import org.midonet.cluster.client.HostBuilder;
import org.midonet.cluster.client.IPAddrGroupBuilder;
import org.midonet.cluster.client.PoolHealthMonitorMapBuilder;
import org.midonet.cluster.client.PortBuilder;
import org.midonet.cluster.client.PortGroupBuilder;
import org.midonet.cluster.client.PortSetBuilder;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.client.TraceConditionsBuilder;
import org.midonet.cluster.client.LoadBalancerBuilder;
import org.midonet.cluster.client.PoolBuilder;
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

    void getIPAddrGroup(UUID uuid, IPAddrGroupBuilder builder);

    void getLoadBalancer(UUID uuid, LoadBalancerBuilder builder);

    void getPool(UUID uuid, PoolBuilder builder);

    void getPortGroup(UUID uuid, PortGroupBuilder builder);

    void getPoolHealthMonitorMap(PoolHealthMonitorMapBuilder builder);

    void getHealthMonitor(UUID uuid, HealthMonitorBuilder builder);

    void subscribeBgp(UUID portID, BGPListBuilder builder);
}
