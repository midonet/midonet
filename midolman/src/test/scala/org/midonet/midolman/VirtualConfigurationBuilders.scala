/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman

import java.util.UUID
import java.util.{HashSet => JSet}

import scala.collection.JavaConversions._

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.{Bridge => ClusterBridge, Router => ClusterRouter, _}
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.dhcp.Subnet6
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.{RouterPort, BridgePort}
import org.midonet.cluster.data.rules.{ForwardNatRule, ReverseNatRule}
import org.midonet.cluster.data.rules.{JumpRule, LiteralRule}
import org.midonet.cluster.data.zones.GreTunnelZone
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.packets.{IPv4Subnet, TCP, MAC}
import org.midonet.midolman.state.DirectoryCallback
import org.midonet.midolman.state.DirectoryCallback.Result
import org.apache.zookeeper.KeeperException

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
        Thread.sleep(50)
        chain
    }

    def newOutboundChainOnBridge(name: String, bridge: ClusterBridge): Chain = {
        val chain = createChain(name, None)
        bridge.setOutboundFilter(chain.getId)
        clusterDataClient().bridgesUpdate(bridge)
        Thread.sleep(50)
        chain
    }

    def newInboundChainOnRouter(name: String, router: ClusterRouter): Chain = {
        val chain = createChain(name, None)
        router.setInboundFilter(chain.getId)
        clusterDataClient().routersUpdate(router)
        Thread.sleep(50)
        chain
    }

    def newOutboundChainOnRouter(name: String, router: ClusterRouter): Chain = {
        val chain = createChain(name, None)
        router.setOutboundFilter(chain.getId)
        clusterDataClient().routersUpdate(router)
        Thread.sleep(50)
        chain
    }

    def createChain(name: String, id: Option[UUID] = None): Chain = {
        val chain = new Chain().setName(name)
        if (id.isDefined)
            chain.setId(id.get)
        else
            chain.setId(UUID.randomUUID)
        clusterDataClient().chainsCreate(chain)
        Thread.sleep(50)
        chain
    }

    def newOutboundChainOnPort[PD <: Port.Data, P <: Port[PD, P]]
                              (name: String, port: Port[PD, P],
                               id: UUID): Chain = {
        val chain = createChain(name, Some(id))
        port.setOutboundFilter(id)
        clusterDataClient().portsUpdate(port)
        Thread.sleep(50)
        chain
    }

    def newInboundChainOnPort[PD <: Port.Data, P <: Port[PD, P]]
                             (name: String, port: Port[PD, P],
                              id: UUID): Chain = {
        val chain = new Chain().setName(name).setId(id)
        clusterDataClient().chainsCreate(chain)
        port.setInboundFilter(id)
        clusterDataClient().portsUpdate(port)
        Thread.sleep(50)
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
        Thread.sleep(50)
        clusterDataClient().rulesGet(id).asInstanceOf[LiteralRule]
    }

    /**
     * Convenience method for creating a rule that accepts or drops TCP
     * packets addressed to a specific port.
     */
    def newTcpDstRuleOnChain(chain: Chain, pos: Int,
                          dstPort: Int, action: Action): LiteralRule = {
        val condition = newCondition(nwProto = Some(TCP.PROTOCOL_NUMBER),
                                     tpDst = Some(dstPort))
        newLiteralRuleOnChain(chain, pos, condition, action)
    }

    def newIpAddrGroupRuleOnChain(chain: Chain, pos: Int, action: Action,
                                  ipAddrGroupIdDst: Option[UUID],
                                  ipAddrGroupIdSrc: Option[UUID]) {
        val condition = newCondition(ipAddrGroupIdDst = ipAddrGroupIdDst,
                                     ipAddrGroupIdSrc = ipAddrGroupIdSrc)
        newLiteralRuleOnChain(chain, pos, condition, action)
    }

    def newForwardNatRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                                 action: Action, targets: Set[NatTarget],
                                 isDnat: Boolean) : ForwardNatRule = {
        val jTargets = new JSet[NatTarget]()
        jTargets.addAll(targets)
        val rule = new ForwardNatRule(condition, action, jTargets, isDnat).
                        setChainId(chain.getId).setPosition(pos)
        val id = clusterDataClient().rulesCreate(rule)
        Thread.sleep(50)
        clusterDataClient().rulesGet(id).asInstanceOf[ForwardNatRule]
    }

    def newReverseNatRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                         action: Action, isDnat: Boolean) : ReverseNatRule = {
        val rule = new ReverseNatRule(condition, action, isDnat).
            setChainId(chain.getId).setPosition(pos)
        val id = clusterDataClient().rulesCreate(rule)
        Thread.sleep(50)
        clusterDataClient().rulesGet(id).asInstanceOf[ReverseNatRule]
    }

    def removeRuleFromBridge(bridge: ClusterBridge) {
        bridge.setInboundFilter(null)
        clusterDataClient().bridgesUpdate(bridge)
        Thread.sleep(50)
    }

    def newJumpRuleOnChain(chain: Chain, pos: Int, condition: Condition,
                              jumpToChainID: UUID): JumpRule = {
        val rule = new JumpRule(condition).
            setChainId(chain.getId).setPosition(pos).setJumpToChainId(jumpToChainID)
        val id = clusterDataClient().rulesCreate(rule)
        Thread.sleep(50)
        clusterDataClient().rulesGet(id).asInstanceOf[JumpRule]
    }
    def deleteRule(id: UUID) {
        clusterDataClient().rulesDelete(id)
    }

    def createIpAddrGroup(): IpAddrGroup = createIpAddrGroup(UUID.randomUUID())

    def createIpAddrGroup(id: UUID): IpAddrGroup = {
        val ipAddrGroup = new IpAddrGroup(id, new IpAddrGroup.Data())
        clusterDataClient().ipAddrGroupsCreate(ipAddrGroup)
        Thread.sleep(50)
        ipAddrGroup
    }

    def addIpAddrToIpAddrGroup(id: UUID, addr: String): Unit = {
        clusterDataClient().ipAddrGroupAddAddr(id, addr)
    }

    def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit = {
        clusterDataClient().ipAddrGroupRemoveAddr(id, addr)
    }

    def deleteIpAddrGroup(id: UUID) = {
        clusterDataClient().ipAddrGroupsDelete(id)
    }

    def greTunnelZone(name: String): GreTunnelZone = {
        val tunnelZone = new GreTunnelZone().setName("default")
        clusterDataClient().tunnelZonesCreate(tunnelZone)
        Thread.sleep(50)
        tunnelZone
    }

    def newBridge(bridge: ClusterBridge): ClusterBridge = {
        val id = clusterDataClient().bridgesCreate(bridge)
        Thread.sleep(50)
        clusterDataClient().bridgesGet(id)
    }

    def newBridge(name: String): ClusterBridge =
            newBridge(new ClusterBridge().setName(name))

    def newBridgePort(bridge: ClusterBridge): BridgePort = {
        val uuid = clusterDataClient().portsCreate(Ports.bridgePort(bridge))
        Thread.sleep(50)
        // do a portsGet because some fields are set during the creating and are
        // not copied in the port object we pass, eg. TunnelKey
        clusterDataClient().portsGet(uuid).asInstanceOf[BridgePort]
    }

    def newBridgePort(bridge: ClusterBridge,
                              vlanId: Option[Short] = None): BridgePort = {
        val jVlanId: java.lang.Short = if(vlanId.isDefined) vlanId.get else null
        val uuid = clusterDataClient()
                   .portsCreate(Ports.bridgePort(bridge, jVlanId))
        Thread.sleep(50)
        clusterDataClient().portsGet(uuid).asInstanceOf[BridgePort]
    }

    def deletePort(port: Port[_, _], host: Host){
        clusterDataClient().hostsDelVrnPortMapping(host.getId, port.getId)
    }

    def newRouter(router: ClusterRouter): ClusterRouter = {
        val id = clusterDataClient().routersCreate(router)
        Thread.sleep(50)
        clusterDataClient().routersGet(id)
    }

    def newRouter(name: String): ClusterRouter =
            newRouter(new ClusterRouter().setName(name))

    def newRouterPort(router: ClusterRouter, mac: MAC, portAddr: String,
                        nwAddr: String, nwLen: Int): RouterPort = {
        val port = Ports.routerPort(router)
                        .setPortAddr(portAddr)
                        .setNwAddr(nwAddr)
                        .setNwLength(nwLen)
                        .setHwAddr(mac)
        val uuid = clusterDataClient().portsCreate(port)
        Thread.sleep(50)
        clusterDataClient().portsGet(uuid).asInstanceOf[RouterPort]
    }

    def newRouterPort(router: ClusterRouter, mac: MAC, portAddr: IPv4Subnet): RouterPort = {
        newRouterPort(router, mac, portAddr.toUnicastString,
            portAddr.toNetworkAddress.toString, portAddr.getPrefixLen)
    }

    def newInteriorRouterPort(router: ClusterRouter, mac: MAC, portAddr: String,
                              nwAddr: String, nwLen: Int): RouterPort = {
        val port = Ports.routerPort(router)
                        .setPortAddr(portAddr)
                        .setNwAddr(nwAddr)
                        .setNwLength(nwLen)
                        .setHwAddr(mac)
        val uuid = clusterDataClient().portsCreate(port)
        Thread.sleep(50)
        clusterDataClient().portsGet(uuid).asInstanceOf[RouterPort]
    }

    def newRoute(router: ClusterRouter,
                 srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                 nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                 weight: Int): UUID = {
        val uuid = clusterDataClient().routesCreate(new Route()
            .setRouterId(router.getId)
            .setSrcNetworkAddr(srcNw)
            .setSrcNetworkLength(srcNwLen)
            .setDstNetworkAddr(dstNw)
            .setDstNetworkLength(dstNwLen)
            .setNextHop(nextHop)
            .setNextHopPort(nextHopPort)
            .setNextHopGateway(nextHopGateway)
            .setWeight(weight))
        Thread.sleep(50)
        uuid
    }

    def deleteRoute(routeId: UUID) {
        clusterDataClient().routesDelete(routeId)
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

    def linkPorts(port: Port[_, _], peerPort: Port[_, _]) {
        clusterDataClient().portsLink(port.getId, peerPort.getId)
    }

    def materializePort(port: Port[_, _], hostId: UUID, portName: String) {
        clusterDataClient().hostsAddVrnPortMappingAndReturnPort(hostId,
            port.getId, portName)

        clusterDataClient().portsSetLocalAndActive(port.getId, true)

        if (port.isInstanceOf[BridgePort]) {
            clusterDataClient().portSetsAsyncAddHost(port.getDeviceId, hostId,
                new DirectoryCallback.Add {
                    override def onSuccess(result: Result[String]) {
                    }
                    override def onTimeout() {
                    }
                    override def onError(e: KeeperException) {
                    }
                })
        }
    }

    def newCondition(nwProto: Option[Byte] = None,
                     tpDst: Option[Int] = None, tpSrc: Option[Int] = None,
                     ipAddrGroupIdDst: Option[UUID] = None,
                     ipAddrGroupIdSrc: Option[UUID] = None): Condition = {
        val c = new Condition()
        if (ipAddrGroupIdDst.isDefined)
            c.ipAddrGroupIdDst = ipAddrGroupIdDst.get
        if (ipAddrGroupIdSrc.isDefined)
            c.ipAddrGroupIdSrc = ipAddrGroupIdSrc.get
        if (nwProto.isDefined)
            c.nwProto = Byte.box(nwProto.get)
        if (tpDst.isDefined)
            c.tpDst = new org.midonet.util.Range(Int.box(tpDst.get))
        if (tpSrc.isDefined)
            c.tpSrc = new org.midonet.util.Range(Int.box(tpSrc.get))
        c
    }

    def newIPAddrGroup(id: Option[UUID]): UUID = {
        val ipAddrGroup = id match {
            case None => new IpAddrGroup()
            case Some(id) => new IpAddrGroup(id)
        }
        clusterDataClient().ipAddrGroupsCreate(ipAddrGroup)
    }

    def addAddrToIpAddrGroup(id: UUID, addr: String) {
        clusterDataClient().ipAddrGroupAddAddr(id, addr)
    }

    def removeAddrFromIpAddrGroup(id: UUID, addr: String) {
        clusterDataClient().ipAddrGroupRemoveAddr(id, addr)
    }
}
