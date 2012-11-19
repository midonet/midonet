/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman

import scala.collection.JavaConversions._
import java.util.UUID
import java.util.{HashSet => JSet}

import com.midokura.midolman.layer3.Route.NextHop
import rules.{NatTarget, Condition}
import rules.Condition
import com.midokura.midolman.rules.RuleResult.Action
import com.midokura.midonet.cluster.DataClient
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Chain,
        Port, Ports, Router => ClusterRouter, Route}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.data.rules.{NatRule, LiteralRule,
                                                ForwardNatRule, ReverseNatRule}
import com.midokura.midonet.cluster.data.rules.{JumpRule, LiteralRule}
import com.midokura.midonet.cluster.data.ports.{LogicalBridgePort,
        LogicalRouterPort, MaterializedBridgePort, MaterializedRouterPort}
import com.midokura.midonet.cluster.data.zones.GreTunnelZone
import com.midokura.packets.MAC
import com.midokura.midonet.cluster.data.dhcp.Subnet


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
        val chain = createChain(name, None)
        bridge.setInboundFilter(chain.getId)
        clusterDataClient().bridgesUpdate(bridge)
        chain
    }

    def newInboundChainOnRouter(name: String, router: ClusterRouter): Chain = {
        val chain = createChain(name, None)
        router.setInboundFilter(chain.getId)
        clusterDataClient().routersUpdate(router)
        chain
    }

    def newOutboundChainOnRouter(name: String, router: ClusterRouter): Chain = {
        val chain = createChain(name, None)
        router.setOutboundFilter(chain.getId)
        clusterDataClient().routersUpdate(router)
        chain
    }

    def createChain(name: String, id: Option[UUID]): Chain = {
        val chain = new Chain().setName(name)
        if (id.isDefined)
            chain.setId(id.get)
        else
            chain.setId(UUID.randomUUID)
        clusterDataClient().chainsCreate(chain)
        chain
    }

    def newOutboundChainOnPort[PD <: Port.Data, P <: Port[PD, P]]
                              (name: String, port: Port[PD, P],
                               id: UUID): Chain = {
        val chain = createChain(name, Some(id))
        port.setOutboundFilter(id)
        clusterDataClient().portsUpdate(port)
        chain
    }

    def newInboundChainOnPort[PD <: Port.Data, P <: Port[PD, P]]
                             (name: String, port: Port[PD, P],
                              id: UUID): Chain = {
        val chain = new Chain().setName(name).setId(id)
        clusterDataClient().chainsCreate(chain)
        port.setInboundFilter(id)
        clusterDataClient().portsUpdate(port)
        chain
    }

    def newOutboundChainOnPort[PD <: Port.Data, P <: Port[PD, P]]
                              (name: String, port: Port[PD, P]): Chain =
        newOutboundChainOnPort(name, port, UUID.randomUUID)

    def newInboundChainOnPort[PD <: Port.Data, P <: Port[PD, P]]
                             (name: String, port: Port[PD, P]): Chain =
        newInboundChainOnPort(name, port, UUID.randomUUID)

    def newLiteralRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                              action: Action): LiteralRule = {
        val rule = new LiteralRule(condition, action).
                        setChainId(chain.getId).setPosition(pos)
        val id = clusterDataClient().rulesCreate(rule)
        clusterDataClient().rulesGet(id).asInstanceOf[LiteralRule]
    }

    def newForwardNatRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                                 action: Action, targets: Set[NatTarget],
                                 isDnat: Boolean) : ForwardNatRule = {
        val jTargets = new JSet[NatTarget]()
        jTargets.addAll(targets)
        val rule = new ForwardNatRule(condition, action, jTargets, isDnat).
                        setChainId(chain.getId).setPosition(pos)
        val id = clusterDataClient().rulesCreate(rule)
        clusterDataClient().rulesGet(id).asInstanceOf[ForwardNatRule]
    }

    def newReverseNatRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                         action: Action, isDnat: Boolean) : ReverseNatRule = {
        val rule = new ReverseNatRule(condition, action, isDnat).
            setChainId(chain.getId).setPosition(pos)
        val id = clusterDataClient().rulesCreate(rule)
        clusterDataClient().rulesGet(id).asInstanceOf[ReverseNatRule]
    }

    def removeRuleFromBridge(bridge: ClusterBridge) {
        bridge.setInboundFilter(null)
        clusterDataClient().bridgesUpdate(bridge)
    }

    def newJumpRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                              jumpToChainID: UUID): JumpRule = {
        val rule = new JumpRule(condition).
            setChainId(chain.getId).setPosition(pos).setJumpToChainId(jumpToChainID)
        val id = clusterDataClient().rulesCreate(rule)
        clusterDataClient().rulesGet(id).asInstanceOf[JumpRule]
    }
    def deleteRule(id: UUID) {
        clusterDataClient().rulesDelete(id)
    }

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

    def addDhcpSubnet(bridge : ClusterBridge,
                      subnet : Subnet) = {
        clusterDataClient().dhcpSubnetsCreate(bridge.getId, subnet)
    }

    def addDhcpHost(bridge : ClusterBridge, subnet : Subnet,
                    host : com.midokura.midonet.cluster.data.dhcp.Host) = {
        clusterDataClient().dhcpHostsCreate(bridge.getId,
                                            subnet.getSubnetAddr, host)
    }
}
