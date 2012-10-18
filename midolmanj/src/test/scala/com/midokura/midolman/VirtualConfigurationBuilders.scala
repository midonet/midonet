/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman

import java.util.UUID
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Router => ClusterRouter, Route, Port, Ports}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.DataClient
import com.midokura.midonet.cluster.data.zones.GreTunnelZone
import com.midokura.midonet.cluster.data.ports.{LogicalBridgePort,
                                                LogicalRouterPort,
                                                MaterializedRouterPort,
                                                MaterializedBridgePort}
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

    def newExteriorBridgePort(bridge: ClusterBridge, port: MaterializedBridgePort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port))
            .asInstanceOf[MaterializedBridgePort]

    def newExteriorBridgePort(bridge: ClusterBridge):MaterializedBridgePort =
        newExteriorBridgePort(bridge, Ports.materializedBridgePort(bridge))

    def newInteriorBridgePort(bridge: ClusterBridge, port: LogicalBridgePort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port))
            .asInstanceOf[LogicalBridgePort]

    def newInteriorBridgePort(bridge: ClusterBridge):LogicalBridgePort =
        newInteriorBridgePort(bridge, Ports.logicalBridgePort(bridge))

    def materializePort(port: Port[_, _], host: Host, name: String) {
        clusterDataClient().hostsAddVrnPortMapping(host.getId, port.getId, name)
    }

    def deletePort(port: Port[_, _], host: Host){
        clusterDataClient().hostsDelVrnPortMapping(host.getId, port.getId)
    }

    def newRouter(router: ClusterRouter): ClusterRouter = {
        clusterDataClient().routersGet(clusterDataClient().routersCreate(router))
    }

    def newRouter(name: String): ClusterRouter =
            newRouter(new ClusterRouter().setName(name))

    def newExteriorRouterPort(router: ClusterRouter, port: MaterializedRouterPort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port))
            .asInstanceOf[MaterializedRouterPort]

    def newExteriorRouterPort(router: ClusterRouter, mac: MAC, portAddr: String,
                        nwAddr: String, nwLen: Int): MaterializedRouterPort = {
        newExteriorRouterPort(router, Ports.materializedRouterPort(router)
            .setPortAddr(portAddr)
            .setNwAddr(nwAddr)
            .setNwLength(nwLen)
            .setHwAddr(mac))
    }

    def newInteriorRouterPort(router: ClusterRouter, port: LogicalRouterPort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port))
            .asInstanceOf[LogicalRouterPort]

    def newInteriorRouterPort(router: ClusterRouter, mac: MAC, portAddr: String,
                              nwAddr: String, nwLen: Int): LogicalRouterPort = {
        newInteriorRouterPort(router, Ports.logicalRouterPort(router)
            .setPortAddr(portAddr)
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
