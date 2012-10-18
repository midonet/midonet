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
import com.midokura.midonet.cluster.{ClusterRouterManager, DataClient}
import com.midokura.midonet.cluster.data.ports.{MaterializedBridgePort,
        MaterializedRouterPort}
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
            clusterDataClient().bridgesCreate(
                bridge
            )
        )
    }

    def newBridge(name: String): ClusterBridge =
            newBridge(new ClusterBridge().setName("bridge"))

    def newPortOnBridge(bridge: ClusterBridge, port: MaterializedBridgePort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port))
            .asInstanceOf[MaterializedBridgePort]

    def newPortOnBridge(bridge: ClusterBridge): MaterializedBridgePort =
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
                        nwAddr: String, nwLen: Int): MaterializedRouterPort = {
        newPortOnRouter(router, Ports.materializedRouterPort(router)
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
