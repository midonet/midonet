/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman

import java.util.UUID
import com.midokura.midolman.layer3.Route.NextHop
import com.midokura.midolman.rules.Condition
import com.midokura.midolman.rules.RuleResult.Action
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Chain,
        Port, Ports, Router => ClusterRouter, Route}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.data.rules.LiteralRule
import com.midokura.midonet.cluster.DataClient
import com.midokura.midonet.cluster.data.ports.{LogicalBridgePort,
        LogicalRouterPort, MaterializedBridgePort, MaterializedRouterPort}
import com.midokura.midonet.cluster.data.zones.GreTunnelZone
import com.midokura.packets.MAC


trait VirtualConfigurationBuilders {

    protected def clusterDataClient(): DataClient

    def newHost(name: String, id: UUID): Host = {
        val host = new Host().setName(name)
        clusterDataClient().hostsCreate(id, host)
        host.setId(id)
        host
    }

    def newHost(name: String): Host = newHost(name, UUID.randomUUID())

    def newInboundChainOnBridge(name: String, bridge: ClusterBridge): Chain = {
        val chain = new Chain().setName(name).setId(UUID.randomUUID)
        clusterDataClient().chainsCreate(chain)
        bridge.setInboundFilter(chain.getId)
        clusterDataClient().bridgesUpdate(bridge)
        chain
    }

    def newOutboundChainOnPort(name: String, port: Port[_, _],
                               id: UUID): Chain = {
        val chain = new Chain().setName(name).setId(id)
        clusterDataClient().chainsCreate(chain)
        port.setOutboundFilter(id)
        chain
    }

    def newOutboundChainOnPort(name: String, port: Port[_, _]): Chain =
        newOutboundChainOnPort(name, port, UUID.randomUUID)

    def newLiteralRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                              action: Action, id: UUID): LiteralRule = {
        val rule = new LiteralRule(condition, action).setId(id)
                        .setChainId(chain.getId).setPosition(pos)
        clusterDataClient().rulesCreate(rule)
        rule
    }

    def newLiteralRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                              action: Action): LiteralRule =
        newLiteralRuleOnChain(chain, pos, condition, action, UUID.randomUUID)

    def greTunnelZone(name: String): GreTunnelZone = {
        val tunnelZone = new GreTunnelZone().setName("default")
        clusterDataClient().tunnelZonesCreate(tunnelZone)
        tunnelZone
    }

    def newBridge(bridge: ClusterBridge): ClusterBridge = {
        clusterDataClient().bridgesGet(
            clusterDataClient().bridgesCreate(bridge))
    }

    def newBridge(name: String): ClusterBridge =
            newBridge(new ClusterBridge().setName(name))

    def newExteriorBridgePort(bridge: ClusterBridge): MaterializedBridgePort = {
        val uuid = clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))
        // do a portsGet because some fields are set during the creating and are
        // not copied in the port object we pass, eg. TunnelKey
        clusterDataClient().portsGet(uuid).asInstanceOf[MaterializedBridgePort]
    }

    def newInteriorBridgePort(bridge: ClusterBridge): LogicalBridgePort = {
        val uuid = clusterDataClient().portsCreate(Ports.logicalBridgePort(bridge))
        clusterDataClient().portsGet(uuid).asInstanceOf[LogicalBridgePort]
    }

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

    def newExteriorRouterPort(router: ClusterRouter, mac: MAC, portAddr: String,
                        nwAddr: String, nwLen: Int): MaterializedRouterPort = {
        val port = Ports.materializedRouterPort(router)
                        .setPortAddr(portAddr)
                        .setNwAddr(nwAddr)
                        .setNwLength(nwLen)
                        .setHwAddr(mac)
        val uuid = clusterDataClient().portsCreate(port)
        clusterDataClient().portsGet(uuid).asInstanceOf[MaterializedRouterPort]
    }

    def newInteriorRouterPort(router: ClusterRouter, mac: MAC, portAddr: String,
                              nwAddr: String, nwLen: Int): LogicalRouterPort = {
        val port = Ports.logicalRouterPort(router)
                        .setPortAddr(portAddr)
                        .setNwAddr(nwAddr)
                        .setNwLength(nwLen)
                        .setHwAddr(mac)
        val uuid = clusterDataClient().portsCreate(port)
        clusterDataClient().portsGet(uuid).asInstanceOf[LogicalRouterPort]
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
