/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.util

import java.util.UUID
import java.util.{HashSet => JSet}

import scala.util.Random

import scala.collection.JavaConversions._

import com.google.inject.Inject

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.{Bridge,
                                 Router => ClusterRouter,
                                 PortGroup => ClusterPortGroup,
                                 _}
import org.midonet.cluster.data.dhcp.{Host => DhcpHost}
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.dhcp.Subnet6
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.{RouterPort, BridgePort, VxLanPort}
import org.midonet.cluster.data.rules.{ForwardNatRule, ReverseNatRule}
import org.midonet.cluster.data.rules.{JumpRule, LiteralRule}
import org.midonet.cluster.state.LegacyStorage
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{FragmentPolicy, Condition, NatTarget}
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.packets.{IPv4Addr, IPv4Subnet, TCP, MAC}
import org.midonet.cluster.data.l4lb.{PoolMember, Pool, VIP, LoadBalancer,
                                      HealthMonitor}
import org.midonet.midolman.state.l4lb.{PoolLBMethod, VipSessionPersistence, LBStatus}


class LegacyVirtualConfigurationBuilders @Inject()(clusterDataClient: DataClient,
                                         stateStorage: LegacyStorage)
                                         extends VirtualConfigurationBuilders {

    def clusterDataClient(): DataClient = clusterDataClient

    def stateStorage(): LegacyStorage = stateStorage

    override def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID = {
        val host = new Host().setName(name).setTunnelZones(tunnelZones)
        clusterDataClient().hostsCreate(id, host)
        id
    }

    override def newHost(name: String, id: UUID): UUID = newHost(name, id, Set.empty)
    override def newHost(name: String): UUID = newHost(name, UUID.randomUUID())
    override def isHostAlive(id: UUID): Boolean = clusterDataClient().hostsGet(id).getIsAlive

    def newInboundChainOnBridge(name: String, bridgeId: UUID): Chain = {
        val bridge = clusterDataClient().bridgesGet(bridgeId)
        val chain = newChain(name, None)
        bridge.setInboundFilter(chain.getId)
        clusterDataClient().bridgesUpdate(bridge)
        Thread.sleep(50)
        chain
    }

    def newOutboundChainOnBridge(name: String, bridgeId: UUID): Chain = {
        val bridge = clusterDataClient().bridgesGet(bridgeId)
        val chain = newChain(name, None)
        bridge.setOutboundFilter(chain.getId)
        clusterDataClient().bridgesUpdate(bridge)
        Thread.sleep(50)
        chain
    }

    def newInboundChainOnRouter(name: String, router: ClusterRouter): Chain = {
        val chain = newChain(name, None)
        router.setInboundFilter(chain.getId)
        clusterDataClient().routersUpdate(router)
        Thread.sleep(50)
        chain
    }

    def newOutboundChainOnRouter(name: String, router: ClusterRouter): Chain = {
        val chain = newChain(name, None)
        router.setOutboundFilter(chain.getId)
        clusterDataClient().routersUpdate(router)
        Thread.sleep(50)
        chain
    }

    def newChain(name: String, id: Option[UUID] = None): Chain = {
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
        val chain = newChain(name, Some(id))
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
        val rule = new LiteralRule(condition)
                       .setChainId(chain.getId).setPosition(pos)
                       .setAction(action)
        val id = clusterDataClient().rulesCreate(rule)
        Thread.sleep(50)
        clusterDataClient().rulesGet(id).asInstanceOf[LiteralRule]
    }

    /**
     * Convenience method for creating a rule that accepts or drops TCP
     * packets addressed to a specific port.
     */
    def newTcpDstRuleOnChain(
            chain: Chain, pos: Int, dstPort: Int, action: Action,
            fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED)
    : LiteralRule = {
        val condition = newCondition(nwProto = Some(TCP.PROTOCOL_NUMBER),
                                     tpDst = Some(dstPort),
                                     fragmentPolicy = fragmentPolicy)
        newLiteralRuleOnChain(chain, pos, condition, action)
    }

    def newIpAddrGroupRuleOnChain(chain: Chain, pos: Int, action: Action,
                                  ipAddrGroupIdDst: Option[UUID],
                                  ipAddrGroupIdSrc: Option[UUID]) = {
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

    def removeRuleFromBridge(bridgeId: UUID) {
        val bridge = clusterDataClient().bridgesGet(bridgeId)
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

    def newFragmentRuleOnChain(chain: Chain, pos: Int,
                               fragmentPolicy: FragmentPolicy,
                               action: Action) = {
        val condition = newCondition(fragmentPolicy = fragmentPolicy)
        newLiteralRuleOnChain(chain, pos, condition, action)
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

    def greTunnelZone(name: String): UUID = {
        val tunnelZone = new TunnelZone().
            setName("default").
            setType(TunnelZone.Type.gre)
        clusterDataClient().tunnelZonesCreate(tunnelZone)
        Thread.sleep(50)
        tunnelZone.getId
    }

    def newBridge(name: String): UUID = {
        val bridge = new Bridge().setName(name)
        val id = clusterDataClient().bridgesCreate(bridge)
        Thread.sleep(50)
        clusterDataClient().bridgesGet(id)
        id
    }

    def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit = {
        val br = clusterDataClient().bridgesGet(bridge)
        br.setAdminStateUp(state)
        clusterDataClient().bridgesUpdate(br)
    }

    def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit = {
        clusterDataClient.bridgeAddIp4Mac(bridge, ip, mac)
    }


    def newBridgePort(bridgeId: UUID): BridgePort = {
        val bridge = clusterDataClient().bridgesGet(bridgeId)
        newBridgePort(bridgeId, Ports.bridgePort(bridge))
    }

    def newBridgePort(bridge: UUID, port: BridgePort) = {
        port.setDeviceId(bridge)
        val uuid = clusterDataClient().portsCreate(port)
        Thread.sleep(50)
        // do a portsGet because some fields are set during the creating and are
        // not copied in the port object we pass, eg. TunnelKey
        clusterDataClient().portsGet(uuid).asInstanceOf[BridgePort]
    }

    def newBridgePort(bridgeId: UUID,
                      vlanId: Option[Short] = None): BridgePort = {
        val bridge = clusterDataClient().bridgesGet(bridgeId)
        val jVlanId: java.lang.Short = if(vlanId.isDefined) vlanId.get else null
        val uuid = clusterDataClient()
                   .portsCreate(Ports.bridgePort(bridge, jVlanId))
        Thread.sleep(50)
        clusterDataClient().portsGet(uuid).asInstanceOf[BridgePort]
    }

    def newVxLanPort(bridge: UUID, port: VxLanPort): VxLanPort = {
        port.setDeviceId(bridge)
        val uuid = clusterDataClient().portsCreate(port)
        clusterDataClient().portsGet(uuid).asInstanceOf[VxLanPort]
    }

    def deletePort(port: Port[_, _], hostId: UUID){
        clusterDataClient().hostsDelVrnPortMapping(hostId, port.getId)
    }

    def newPortGroup(name: String, stateful: Boolean = false) = {
        val pg = new ClusterPortGroup().setName(name).setStateful(stateful)
        val id = clusterDataClient().portGroupsCreate(pg)
        Thread.sleep(50)
        clusterDataClient().portGroupsGet(id)
    }

    def updatePortGroup(pg: ClusterPortGroup) = {
        clusterDataClient().portGroupsUpdate(pg)
    }

    def newPortGroupMember(pgId: UUID, portId: UUID) = {
        clusterDataClient().portGroupsAddPortMembership(pgId, portId)
    }

    def deletePortGroupMember(pgId: UUID, portId: UUID) = {
        clusterDataClient().portGroupsRemovePortMembership(pgId, portId)
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

    def addDhcpSubnet(bridge : UUID,
                      subnet : Subnet) = {
        clusterDataClient().dhcpSubnetsCreate(bridge, subnet)
    }

    def addDhcpHost(bridge : UUID, subnet : Subnet,
                    host : org.midonet.cluster.data.dhcp.Host) = {
        clusterDataClient().dhcpHostsCreate(bridge, subnet.getSubnetAddr, host)
    }

    def updatedhcpHost(bridge: UUID,
                       subnet: Subnet, host: DhcpHost) = {
        clusterDataClient().dhcpHostsUpdate(
            bridge, subnet.getSubnetAddr, host)
    }

    def addDhcpSubnet6(bridge : UUID,
                       subnet : Subnet6) = {
        clusterDataClient().dhcpSubnet6Create(bridge, subnet)
    }

    def addDhcpV6Host(bridge : UUID, subnet : Subnet6,
                    host : org.midonet.cluster.data.dhcp.V6Host) = {
        clusterDataClient().dhcpV6HostCreate(bridge,
                                              subnet.getPrefix, host)
    }

    def linkPorts(port: Port[_, _], peerPort: Port[_, _]) {
        clusterDataClient().portsLink(port.getId, peerPort.getId)
    }

    def materializePort(port: Port[_, _], hostId: UUID, portName: String): Unit =
        materializePort(port.getId, hostId, portName)

    def materializePort(port: UUID, hostId: UUID, portName: String): Unit = {
        clusterDataClient().hostsAddVrnPortMappingAndReturnPort(
            hostId, port, portName)

        stateStorage.setPortLocalAndActive(port, hostId, true)
    }

    def newCondition(
            nwProto: Option[Byte] = None,
            tpDst: Option[Int] = None,
            tpSrc: Option[Int] = None,
            ipAddrGroupIdDst: Option[UUID] = None,
            ipAddrGroupIdSrc: Option[UUID] = None,
            fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED)
            : Condition = {
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
        c.fragmentPolicy = fragmentPolicy
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

    // L4LB
    def newLoadBalancer(id: UUID = UUID.randomUUID): LoadBalancer = {
        val loadBalancer = new LoadBalancer()
        loadBalancer.setAdminStateUp(true)
        loadBalancer.setId(id)
        clusterDataClient().loadBalancerCreate(loadBalancer)
        Thread.sleep(50)
        loadBalancer
    }

    def deleteLoadBalancer(id: UUID) {
        clusterDataClient().loadBalancerDelete(id)
    }

    def setLoadBalancerOnRouter(loadBalancer: LoadBalancer, router: ClusterRouter): Unit = {
        if (loadBalancer != null) {
            router.setLoadBalancer(loadBalancer.getId)
        } else {
            router.setLoadBalancer(null)
        }
        clusterDataClient().routersUpdate(router)
    }

    def setLoadBalancerDown(loadBalancer: LoadBalancer) {
        loadBalancer.setAdminStateUp(false)
        clusterDataClient().loadBalancerUpdate(loadBalancer)
    }

    def createVip(pool: Pool): VIP = createVip(pool, "10.10.10.10", 10)

    def createVip(pool: Pool, address: String, port: Int): VIP = {
        val vip = new VIP()
        vip.setId(UUID.randomUUID)
        vip.setAddress(address)
        vip.setPoolId(pool.getId)
        // Set the load balancer ID manually. This should be done automatically
        // when we go through the REST API.
        vip.setLoadBalancerId(pool.getLoadBalancerId)
        vip.setProtocolPort(port)
        vip.setAdminStateUp(true)
        clusterDataClient().vipCreate(vip)
        Thread.sleep(50)
        // Getting the created VIP to see the actual model stored in
        // ZooKeeper because `loadBalancerId` would be populated though
        // the associated pool.
        clusterDataClient().vipGet(vip.getId)
    }

    def deleteVip(vip: VIP): Unit = clusterDataClient().vipDelete(vip.getId)

    def removeVipFromLoadBalancer(vip: VIP, loadBalancer: LoadBalancer) {
        vip.setLoadBalancerId(null)
        clusterDataClient().vipUpdate(vip)
    }

    def createRandomVip(pool: Pool): VIP = {
        val rand = new Random()
        val vip = new VIP()
        vip.setId(UUID.randomUUID)
        vip.setAddress("10.10.10." + Integer.toString(rand.nextInt(200) +1))
        vip.setProtocolPort(rand.nextInt(1000) + 1)
        vip.setAdminStateUp(true)
        vip.setPoolId(pool.getId)
        clusterDataClient().vipCreate(vip)
        Thread.sleep(50)
        // Getting the created VIP to see the actual model stored in
        // ZooKeeper because `loadBalancerId` would be populated though
        // the associated pool.
        clusterDataClient().vipGet(vip.getId)
    }

    def setVipPool(vip: VIP, pool: Pool) {
        vip.setPoolId(pool.getId)
        clusterDataClient().vipUpdate(vip)
    }

    def setVipAdminStateUp(vip: VIP, adminStateUp: Boolean) {
        vip.setAdminStateUp(adminStateUp)
        clusterDataClient().vipUpdate(vip)
    }

    def vipEnableStickySourceIP(vip: VIP) {
        vip.setSessionPersistence(VipSessionPersistence.SOURCE_IP)
        clusterDataClient().vipUpdate(vip)
    }

    def vipDisableStickySourceIP(vip: VIP) {
        vip.setSessionPersistence(null)
        clusterDataClient().vipUpdate(vip)
    }

    def newHealthMonitor(id: UUID = UUID.randomUUID(),
                           adminStateUp: Boolean = true,
                           delay: Int = 2,
                           maxRetries: Int = 2,
                           timeout: Int = 2): HealthMonitor = {
        val hm = new HealthMonitor()
        hm.setId(id)
        hm.setAdminStateUp(adminStateUp)
        hm.setDelay(delay)
        hm.setMaxRetries(maxRetries)
        hm.setTimeout(timeout)
        clusterDataClient().healthMonitorCreate(hm)
        Thread.sleep(50)
        hm
    }

    def newRandomHealthMonitor
            (id: UUID = UUID.randomUUID()): HealthMonitor = {
        val rand = new Random()
        val hm = new HealthMonitor()
        hm.setId(id)
        hm.setAdminStateUp(true)
        hm.setDelay(rand.nextInt(100) + 1)
        hm.setMaxRetries(rand.nextInt(100) + 1)
        hm.setTimeout(rand.nextInt(100) + 1)
        clusterDataClient().healthMonitorCreate(hm)
        Thread.sleep(50)
        hm
    }

    def setHealthMonitorDelay(hm: HealthMonitor, delay: Int) = {
        hm.setDelay(delay)
        clusterDataClient().healthMonitorUpdate(hm)
    }

    def deleteHealthMonitor(hm: HealthMonitor) {
        clusterDataClient().healthMonitorDelete(hm.getId)
    }

    def newPool(loadBalancer: LoadBalancer,
                id: UUID = UUID.randomUUID,
                adminStateUp: Boolean = true,
                lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                hmId: UUID = null): Pool = {
        val pool = new Pool()
        pool.setLoadBalancerId(loadBalancer.getId)
        pool.setHealthMonitorId(hmId)
        pool.setAdminStateUp(adminStateUp)
        pool.setLbMethod(lbMethod)
        pool.setLoadBalancerId(loadBalancer.getId)
        pool.setId(id)
        clusterDataClient().poolCreate(pool)
        Thread.sleep(50)
        pool
    }

    def setPoolHealthMonitor(pool: Pool, hmId: UUID) = {
        pool.setHealthMonitorId(hmId)
        clusterDataClient().poolUpdate(pool)
    }

    def setPoolAdminStateUp(pool: Pool, adminStateUp: Boolean) {
        pool.setAdminStateUp(adminStateUp)
        clusterDataClient().poolUpdate(pool)
    }

    def setPoolLbMethod(pool: Pool, lbMethod: PoolLBMethod) {
        pool.setLbMethod(lbMethod)
        clusterDataClient().poolUpdate(pool)
    }

    def newPoolMember(pool: Pool): PoolMember = {
        newPoolMember(pool, "10.10.10.10", 10)
    }

    def newPoolMember(pool: Pool, address: String, port: Int,
                         weight: Int = 1)
    : PoolMember = {
        val poolMember = new PoolMember()
        poolMember.setId(UUID.randomUUID)
        poolMember.setAdminStateUp(true)
        poolMember.setStatus(LBStatus.ACTIVE)
        poolMember.setAddress(address)
        poolMember.setProtocolPort(port)
        poolMember.setPoolId(pool.getId)
        poolMember.setWeight(weight)
        clusterDataClient().poolMemberCreate(poolMember)
        Thread.sleep(50)
        poolMember
    }

    def updatePoolMember(poolMember: PoolMember,
                         poolId: Option[UUID] = None,
                         adminStateUp: Option[Boolean] = None,
                         weight: Option[Integer] = None,
                         status: Option[LBStatus] = None) {
        poolId.foreach(poolMember.setPoolId)
        adminStateUp.foreach(poolMember.setAdminStateUp)
        weight.foreach(poolMember.setWeight(_))
        status.foreach(poolMember.setStatus)
        clusterDataClient().poolMemberUpdate(poolMember)
    }

    def deletePoolMember(poolMember: PoolMember): Unit =
        clusterDataClient().poolMemberDelete(poolMember.getId)

    def setPoolMemberAdminStateUp(poolMember: PoolMember,
                                  adminStateUp: Boolean) =
        updatePoolMember(poolMember, adminStateUp = Some(adminStateUp))

    def setPoolMemberHealth(poolMember: PoolMember,
                            status: LBStatus) =
        updatePoolMember(poolMember, status = Some(status))
}
