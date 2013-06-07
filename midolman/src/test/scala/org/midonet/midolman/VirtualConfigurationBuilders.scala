/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman

import scala.collection.JavaConversions._
import java.util.UUID
import java.util.{HashSet => JSet}

import org.midonet.midolman.layer3.Route.NextHop
import rules.NatTarget
import rules.Condition
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.{Bridge => ClusterBridge, Chain,
        Port, Ports, Router => ClusterRouter, Route,
        VlanAwareBridge => ClusterVlanAwareBridge}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.rules.{ForwardNatRule, ReverseNatRule}
import org.midonet.cluster.data.rules.{JumpRule, LiteralRule}
import org.midonet.cluster.data.ports._
import org.midonet.cluster.data.zones.GreTunnelZone
import org.midonet.packets.MAC
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.dhcp.Subnet6
import scala.Some


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

    def newVlanAwareBridge(name: String): ClusterVlanAwareBridge = {
        val br = new ClusterVlanAwareBridge().setName(name)
        clusterDataClient()
            .vlanBridgesGet(clusterDataClient().vlanBridgesCreate(br))
    }

    def newVlanBridgeTrunkPort(bridge: ClusterVlanAwareBridge): TrunkPort = {
        val uuid = clusterDataClient().portsCreate(Ports.materializedVlanBridgePort(bridge))
        // do a portsGet because some fields are set during the creating and are
        // not copied in the port object we pass, eg. TunnelKey
        clusterDataClient().portsGet(uuid).asInstanceOf[TrunkPort]
    }

    def newInteriorVlanBridgePort(bridge: ClusterVlanAwareBridge,
                                  vlanId: Option[Short]): LogicalVlanBridgePort = {
        val jVlanId: java.lang.Short = if(vlanId.isDefined) vlanId.get else null
        val uuid = clusterDataClient()
                   .portsCreate(Ports.logicalVlanBridgePort(bridge, jVlanId))
        clusterDataClient().portsGet(uuid).asInstanceOf[LogicalVlanBridgePort]
    }

    def newExteriorBridgePort(bridge: ClusterBridge): MaterializedBridgePort = {
        val uuid = clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))
        // do a portsGet because some fields are set during the creating and are
        // not copied in the port object we pass, eg. TunnelKey
        clusterDataClient().portsGet(uuid).asInstanceOf[MaterializedBridgePort]
    }

    def newInteriorBridgePort(bridge: ClusterBridge,
                              vlanId: Option[Short] = None): LogicalBridgePort = {
        val jVlanId: java.lang.Short = if(vlanId.isDefined) vlanId.get else null
        val uuid = clusterDataClient()
                   .portsCreate(Ports.logicalBridgePort(bridge, jVlanId))
        clusterDataClient().portsGet(uuid).asInstanceOf[LogicalBridgePort]
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
                    host : org.midonet.cluster.data.dhcp.Host) = {
        clusterDataClient().dhcpHostsCreate(bridge.getId,
                                            subnet.getSubnetAddr, host)
    }

    def addDhcpSubnet6(bridge : ClusterBridge,
                       subnet : Subnet6) = {
        clusterDataClient().dhcpSubnet6Create(bridge.getId, subnet)
    }

    def addDhcpV6Host(bridge : ClusterBridge, subnet : Subnet6,
                    host : org.midonet.cluster.data.dhcp.V6Host) = {
        clusterDataClient().dhcpV6HostCreate(bridge.getId,
                                              subnet.getPrefix, host)
    }
}
