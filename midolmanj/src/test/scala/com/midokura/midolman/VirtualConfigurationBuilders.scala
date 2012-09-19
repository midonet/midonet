/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman

import java.util.UUID
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Router => ClusterRouter, Route, Port, Ports}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.{ClusterRouterManager, DataClient}
import com.midokura.midonet.cluster.data.zones.GreTunnelZone
import com.midokura.midonet.cluster.data.ports.{MaterializedRouterPort, MaterializedBridgePort}
import com.midokura.packets.MAC
import layer3.Route.NextHop

trait VirtualConfigurationBuilders {

    protected def clusterDataClient(): DataClient

    def newHost(name: String, id: UUID): Host = {
        val host = new Host().setName(name)
        clusterDataClient().hostsCreate(id, host)
        host.setId(id)
        host
    }

    def newHost(name: String): Host = newHost(name, UUID.randomUUID())

    def greTunnelZone(name: String): GreTunnelZone = {
        val tunnelZone = new GreTunnelZone().setName("default")
        clusterDataClient().tunnelZonesCreate(tunnelZone)
        tunnelZone
    }

    def newBridge(bridge: ClusterBridge): ClusterBridge = {
        clusterDataClient().bridgesGet(
            clusterDataClient().bridgesCreate(
                bridge
            )
        )
    }

    def newBridge(name: String): ClusterBridge = newBridge(new ClusterBridge().setName("bridge"))

    def newPortOnBridge(bridge: ClusterBridge, port: MaterializedBridgePort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port))
            .asInstanceOf[MaterializedBridgePort]

    def newPortOnBridge(bridge: ClusterBridge):MaterializedBridgePort =
        newPortOnBridge(bridge, Ports.materializedBridgePort(bridge))


    def materializePort(port: Port[_, _], host: Host, name: String) {
        clusterDataClient().hostsAddVrnPortMapping(host.getId, port.getId, name)
    }

    def newRouter(router: ClusterRouter): ClusterRouter = {
        clusterDataClient().routersGet(clusterDataClient().routersCreate(router))
    }

    def newRouter(name: String): ClusterRouter =
            newRouter(new ClusterRouter().setName(name))

    def newPortOnRouter(router: ClusterRouter, port: MaterializedRouterPort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port))
            .asInstanceOf[MaterializedRouterPort]

    def newPortOnRouter(router: ClusterRouter, mac: MAC, portAddr: String,
                        localNwAddr: String, localNwLen: Int,
                        nwAddr: String, nwLen: Int): MaterializedRouterPort = {
        newPortOnRouter(router, Ports.materializedRouterPort(router)
            .setPortAddr(portAddr)
            .setLocalNwAddr(localNwAddr)
            .setLocalNwLength(localNwLen)
            .setNwAddr(nwAddr)
            .setNwLength(nwLen)
            .setHwAddr(mac))
    }

    def newRoute(router: ClusterRouter,
                 srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                 nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                 weight: Int): UUID = {
        clusterDataClient().routesCreate(new Route()
            .setRouterId(router.getId)
            .setSrcNetworkAddr(srcNw)
            .setSrcNetworkLength(srcNwLen)
            .setDstNetworkAddr(dstNw)
            .setDstNetworkLength(dstNwLen)
            .setNextHop(nextHop)
            .setNextHopPort(nextHopPort)
            .setNextHopGateway(nextHopGateway)
            .setWeight(weight))
    }
}
