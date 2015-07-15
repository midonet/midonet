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

import java.util.{HashSet => JSet, List => JList, UUID}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.util.Random

import com.google.inject.Inject

import org.midonet.cluster.DataClient
import org.midonet.cluster.data._
import org.midonet.cluster.data.dhcp.{ExtraDhcpOpt, Host => DhcpHost, Opt121, Subnet}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.l4lb.{HealthMonitor, LoadBalancer, Pool, PoolMember, VIP}
import org.midonet.cluster.data.rules.{ForwardNatRule, JumpRule, LiteralRule, ReverseNatRule, TraceRule}
import org.midonet.cluster.state.StateTableStorage
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.rules.{Condition, FragmentPolicy, NatTarget}
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus
import org.midonet.midolman.state.l4lb.{LBStatus, PoolLBMethod, VipSessionPersistence}
import org.midonet.midolman.util.VirtualConfigurationBuilders.DhcpOpt121Route
import org.midonet.packets.{IPAddr, IPv4Addr, IPv4Subnet, MAC, TCP}

class LegacyVirtualConfigurationBuilders @Inject()(clusterDataClient: DataClient,
                                                   stateStorage: StateTableStorage,
                                                   hostZkMgr: HostZkManager)
    extends VirtualConfigurationBuilders {

    override def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID = {
        val host = new Host().setName(name).setTunnelZones(tunnelZones)
        clusterDataClient.hostsCreate(id, host)
        id
    }

    override def isHostAlive(id: UUID): Boolean = clusterDataClient.hostsGet(id).getIsAlive
    override def makeHostAlive(id: UUID): Unit = hostZkMgr.makeAlive(id)

    override def addHostVrnPortMapping(host: UUID, port: UUID, iface: String): Unit =
        clusterDataClient.hostsAddVrnPortMapping(host, port, iface)

    override def newInboundChainOnBridge(name: String, bridgeId: UUID): UUID = {
        val bridge = clusterDataClient.bridgesGet(bridgeId)
        val chain = newChain(name, None)
        bridge.setInboundFilter(chain)
        clusterDataClient.bridgesUpdate(bridge)
        Thread.sleep(50)
        chain
    }

    override def newOutboundChainOnBridge(name: String, bridgeId: UUID): UUID = {
        val bridge = clusterDataClient.bridgesGet(bridgeId)
        val chain = newChain(name, None)
        bridge.setOutboundFilter(chain)
        clusterDataClient.bridgesUpdate(bridge)
        Thread.sleep(50)
        chain
    }

    override def newInboundChainOnRouter(name: String, routerId: UUID): UUID = {
        val router = clusterDataClient.routersGet(routerId)
        val chain = newChain(name, None)
        router.setInboundFilter(chain)
        clusterDataClient.routersUpdate(router)
        Thread.sleep(50)
        chain
    }

    override def newOutboundChainOnRouter(name: String, routerId: UUID): UUID = {
        val router = clusterDataClient.routersGet(routerId)
        val chain = newChain(name, None)
        router.setOutboundFilter(chain)
        clusterDataClient.routersUpdate(router)
        Thread.sleep(50)
        chain
    }

    override def newChain(name: String, id: Option[UUID] = None): UUID = {
        val chain = new Chain().setName(name)
        if (id.isDefined)
            chain.setId(id.get)
        else
            chain.setId(UUID.randomUUID)
        clusterDataClient.chainsCreate(chain)
        Thread.sleep(50)
        chain.getId
    }

    override def newOutboundChainOnPort(name: String, portId: UUID,
                                        id: UUID): UUID = {
        val chain = newChain(name, Some(id))
        val port = clusterDataClient.portsGet(portId)
        port.setOutboundFilter(id)
        clusterDataClient.portsUpdate(port)
        Thread.sleep(50)
        chain
    }

    override def newInboundChainOnPort(name: String, portId: UUID,
                                       id: UUID): UUID = {
        val chain = new Chain().setName(name).setId(id)
        clusterDataClient.chainsCreate(chain)
        val port = clusterDataClient.portsGet(portId)
        port.setInboundFilter(id)
        clusterDataClient.portsUpdate(port)
        Thread.sleep(50)
        chain.getId
    }

    override def newLiteralRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                              action: Action): UUID = {
        val rule = new LiteralRule(condition)
                       .setChainId(chain).setPosition(pos)
                       .setAction(action)
        val id = clusterDataClient.rulesCreate(rule)
        Thread.sleep(50)
        id
    }

    override def newTraceRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                     requestId: UUID): UUID = {
        val rule = new TraceRule(requestId, condition, Long.MaxValue)
            .setChainId(chain).setPosition(pos)
        clusterDataClient.rulesCreate(rule)
    }

    /**
     * Convenience method for creating a rule that accepts or drops TCP
     * packets addressed to a specific port.
     */

    override def newForwardNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                 action: Action, targets: Set[NatTarget],
                                 isDnat: Boolean) : UUID = {
        val jTargets = new JSet[NatTarget]()
        jTargets.addAll(targets)
        val rule = new ForwardNatRule(condition, action, jTargets, isDnat).
                        setChainId(chain).setPosition(pos)
        val id = clusterDataClient.rulesCreate(rule)
        Thread.sleep(50)
        id
    }

    override def newReverseNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                         action: Action, isDnat: Boolean) : UUID = {
        val rule = new ReverseNatRule(condition, action, isDnat).
            setChainId(chain).setPosition(pos)
        val id = clusterDataClient.rulesCreate(rule)
        Thread.sleep(50)
        id
    }

    override def removeRuleFromBridge(bridgeId: UUID) {
        val bridge = clusterDataClient.bridgesGet(bridgeId)
        bridge.setInboundFilter(null)
        clusterDataClient.bridgesUpdate(bridge)
        Thread.sleep(50)
    }

    override def newJumpRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                              jumpToChainID: UUID): UUID = {
        val rule = new JumpRule(condition).
            setChainId(chain).setPosition(pos).setJumpToChainId(jumpToChainID)
        val id = clusterDataClient.rulesCreate(rule)
        Thread.sleep(50)
        id
    }

    override def deleteRule(id: UUID) {
        clusterDataClient.rulesDelete(id)
    }

    override def newIpAddrGroup(id: UUID): UUID = {
        val ipAddrGroup = new IpAddrGroup(id, new IpAddrGroup.Data())
        clusterDataClient.ipAddrGroupsCreate(ipAddrGroup)
        Thread.sleep(50)
        id
    }

    override def addIpAddrToIpAddrGroup(id: UUID, addr: String): Unit = {
        clusterDataClient.ipAddrGroupAddAddr(id, addr)
    }

    override def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit = {
        clusterDataClient.ipAddrGroupRemoveAddr(id, addr)
    }

    override def deleteIpAddrGroup(id: UUID) = {
        clusterDataClient.ipAddrGroupsDelete(id)
    }

    override def greTunnelZone(name: String, id: Option[UUID] = None): UUID = {
        val tunnelZone = new TunnelZone().
            setName(name).
            setType(TunnelZone.Type.gre)
        id.foreach(tunnelZone.setId)
        clusterDataClient.tunnelZonesCreate(tunnelZone)
        Thread.sleep(50)
        tunnelZone.getId
    }

    override def addTunnelZoneMember(tz: UUID, host: UUID, ip: IPv4Addr): Unit = {
        clusterDataClient.tunnelZonesAddMembership(
            tz, new TunnelZone.HostConfig(host).setIp(ip))
    }

    override def deleteTunnelZoneMember(tz: UUID, host: UUID): Unit = {
        clusterDataClient.tunnelZonesDeleteMembership(tz, host)
    }

    override def newBridge(name: String, tenant: Option[String] = None): UUID = {
        val bridge = new Bridge().setName(name)
        tenant.foreach(bridge.setProperty(Bridge.Property.tenant_id, _))
        val id = clusterDataClient.bridgesCreate(bridge)
        Thread.sleep(50)
        clusterDataClient.bridgesGet(id)
        id
    }

    override def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit = {
        val br = clusterDataClient.bridgesGet(bridge)
        br.setAdminStateUp(state)
        clusterDataClient.bridgesUpdate(br)
    }

    override def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit = {
        clusterDataClient.bridgeAddIp4Mac(bridge, ip, mac)
    }

    override def deleteBridge(bridge: UUID): Unit = {
        clusterDataClient.bridgesDelete(bridge)
    }

    override def newBridgePort(bridgeId: UUID,
                               host: Option[UUID] = None,
                               interface: Option[String] = None): UUID = {
        val bridge = clusterDataClient.bridgesGet(bridgeId)
        val port = Ports.bridgePort(bridge)
        host match {
            case Some(hostId) => port.setHostId(hostId)
            case None =>
        }
        interface match {
            case Some(iface) => port.setInterfaceName(iface)
            case None =>
        }
        val uuid = clusterDataClient.portsCreate(port)
        Thread.sleep(50)
        // do a portsGet because some fields are set during the creating and are
        // not copied in the port object we pass, eg. TunnelKey
        uuid
    }

    override def setPortAdminStateUp(port: UUID, state: Boolean): Unit = {
        val portdata = clusterDataClient.portsGet(port)
        portdata.setAdminStateUp(state)
        clusterDataClient.portsUpdate(portdata)
    }

    override def deletePort(port: UUID): Unit = {
        clusterDataClient.portsDelete(port)
    }

    override def deletePort(port: UUID, hostId: UUID){
        clusterDataClient.hostsDelVrnPortMapping(hostId, port)
    }

    override def newPortGroup(name: String, stateful: Boolean = false): UUID = {
        val pg = new PortGroup().setName(name).setStateful(stateful)
        val id = clusterDataClient.portGroupsCreate(pg)
        Thread.sleep(50)
        id
    }

    override def setPortGroupStateful(id: UUID, stateful: Boolean): Unit = {
        val pg = clusterDataClient.portGroupsGet(id)
        pg.setStateful(stateful)
        clusterDataClient.portGroupsUpdate(pg)
    }

    override def newPortGroupMember(pgId: UUID, portId: UUID) = {
        clusterDataClient.portGroupsAddPortMembership(pgId, portId)
    }

    override def deletePortGroupMember(pgId: UUID, portId: UUID) = {
        clusterDataClient.portGroupsRemovePortMembership(pgId, portId)
    }

    override def newRouter(name: String): UUID = {
        val router = new Router().setName(name)
        val id = clusterDataClient.routersCreate(router)
        Thread.sleep(50)
        id
    }

    override def setRouterAdminStateUp(routerId: UUID, state: Boolean): Unit = {
        val router = clusterDataClient.routersGet(routerId)
        router.setAdminStateUp(state)
        clusterDataClient.routersUpdate(router)
    }

    override def deleteRouter(router: UUID): Unit = {
        clusterDataClient.routersDelete(router)
    }

    override def newRouterPort(routerId: UUID, mac: MAC, portAddr: String,
                               nwAddr: String, nwLen: Int): UUID = {
        val router = clusterDataClient.routersGet(routerId)
        val port = Ports.routerPort(router)
                        .setPortAddr(portAddr)
                        .setNwAddr(nwAddr)
                        .setNwLength(nwLen)
                        .setHwAddr(mac)
        val uuid = clusterDataClient.portsCreate(port)
        Thread.sleep(50)
        uuid
    }

    override def newVxLanPort(bridge: UUID, mgmtIp: IPv4Addr, mgmtPort: Int,
                              vni: Int, tunnelIp: IPv4Addr, tunnelZone: UUID): UUID = {
        clusterDataClient.bridgeCreateVxLanPort(bridge, mgmtIp, mgmtPort,
                                                  vni, tunnelIp, tunnelZone).getId
    }

    override def newRoute(router: UUID,
                          srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                          nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                          weight: Int): UUID = {
        val uuid = clusterDataClient.routesCreate(new Route()
            .setRouterId(router)
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

    override def deleteRoute(routeId: UUID) {
        clusterDataClient.routesDelete(routeId)
    }

    override def addDhcpSubnet(bridge: UUID,
                               subnet: IPv4Subnet,
                               gw: IPv4Addr,
                               dns: List[IPv4Addr],
                               opt121routes: List[DhcpOpt121Route]): IPv4Subnet = {
        val opt121 = opt121routes.map {
            e => new Opt121().setGateway(e.gw).setRtDstSubnet(e.subnet) }.asJava
        val subnetObj = new Subnet().setSubnetAddr(subnet)
            .setDefaultGateway(gw).setDnsServerAddrs(dns)
            .setOpt121Routes(opt121)
        clusterDataClient.dhcpSubnetsCreate(bridge, subnetObj)
        subnet
    }

    override def addDhcpHost(bridge: UUID, subnet: IPv4Subnet,
                             hostMac: MAC, hostIp: IPv4Addr): MAC = {
        clusterDataClient.dhcpHostsCreate(bridge, subnet,
                                          new DhcpHost().setMAC(
                                              hostMac).setIp(hostIp))
        hostMac
    }
    override def setDhcpHostOptions(bridge: UUID,
                                    subnet: IPv4Subnet, hostMac: MAC,
                                    options: Map[String, String]): Unit = {
        val host: DhcpHost = clusterDataClient.dhcpHostsGet(bridge, subnet, hostMac.toString)

        val opts: JList[ExtraDhcpOpt] = options map { case (k, v) => new ExtraDhcpOpt(k, v) } toList;
        host.setExtraDhcpOpts(opts)
        clusterDataClient.dhcpHostsUpdate(bridge, subnet, host)
    }

    override def linkPorts(port: UUID, peerPort: UUID) {
        clusterDataClient.portsLink(port, peerPort)
    }

    override def unlinkPorts(port: UUID): Unit = {
        clusterDataClient.portsUnlink(port)
    }

    override def materializePort(port: UUID, hostId: UUID, portName: String): Unit = {
        clusterDataClient.hostsAddVrnPortMappingAndReturnPort(
            hostId, port, portName)

        stateStorage.setPortLocalAndActive(port, hostId, true)
    }

    // L4LB
    override def newLoadBalancer(id: UUID = UUID.randomUUID): UUID = {
        val loadBalancer = new LoadBalancer()
        loadBalancer.setAdminStateUp(true)
        loadBalancer.setId(id)
        clusterDataClient.loadBalancerCreate(loadBalancer)
        Thread.sleep(50)
        id
    }

    override def deleteLoadBalancer(id: UUID) {
        clusterDataClient.loadBalancerDelete(id)
    }

    override def setLoadBalancerOnRouter(loadBalancer: UUID, routerId: UUID): Unit = {
        val router = clusterDataClient.routersGet(routerId)
        if (loadBalancer != null) {
            router.setLoadBalancer(loadBalancer)
        } else {
            router.setLoadBalancer(null)
        }
        clusterDataClient.routersUpdate(router)
    }

    override def setLoadBalancerDown(loadBalancerId: UUID) {
        val loadBalancer = clusterDataClient.loadBalancerGet(loadBalancerId)
        loadBalancer.setAdminStateUp(false)
        clusterDataClient.loadBalancerUpdate(loadBalancer)
    }

    override def newVip(poolId: UUID, address: String, port: Int): UUID = {
        val pool = clusterDataClient.poolGet(poolId)
        val vip = new VIP()
        vip.setId(UUID.randomUUID)
        vip.setAddress(address)
        vip.setPoolId(pool.getId)
        // Set the load balancer ID manually. This should be done automatically
        // when we go through the REST API.

        vip.setLoadBalancerId(pool.getLoadBalancerId)
        vip.setProtocolPort(port)
        vip.setAdminStateUp(true)
        clusterDataClient.vipCreate(vip)
        Thread.sleep(50)
        // Getting the created VIP to see the actual model stored in
        // ZooKeeper because `loadBalancerId` would be populated though
        // the associated pool.
        vip.getId
    }

    override def deleteVip(vip: UUID): Unit = clusterDataClient.vipDelete(vip)

    override def matchVip(vipId: UUID, address: IPAddr, protocolPort: Int): Boolean = {
        val vip = clusterDataClient.vipGet(vipId)
        IPAddr.fromString(vip.getAddress).equals(address) &&
            vip.getProtocolPort == protocolPort
    }

    override def newRandomVip(poolId: UUID): UUID = {
        val rand = new Random()
        val vip = new VIP()
        vip.setId(UUID.randomUUID)
        vip.setAddress("10.10.10." + Integer.toString(rand.nextInt(200) +1))
        vip.setProtocolPort(rand.nextInt(1000) + 1)
        vip.setAdminStateUp(true)
        vip.setPoolId(poolId)
        clusterDataClient.vipCreate(vip)
        Thread.sleep(50)
        // Getting the created VIP to see the actual model stored in
        // ZooKeeper because `loadBalancerId` would be populated though
        // the associated pool.
        vip.getId
    }

    override def setVipAdminStateUp(vipId: UUID, adminStateUp: Boolean) {
        val vip = clusterDataClient.vipGet(vipId)
        vip.setAdminStateUp(adminStateUp)
        clusterDataClient.vipUpdate(vip)
    }

    override def vipEnableStickySourceIP(vipId: UUID) {
        val vip = clusterDataClient.vipGet(vipId)
        vip.setSessionPersistence(VipSessionPersistence.SOURCE_IP)
        clusterDataClient.vipUpdate(vip)
    }

    override def vipDisableStickySourceIP(vipId: UUID) {
        val vip = clusterDataClient.vipGet(vipId)
        vip.setSessionPersistence(null)
        clusterDataClient.vipUpdate(vip)
    }

    override def newHealthMonitor(id: UUID = UUID.randomUUID(),
                                  adminStateUp: Boolean = true,
                                  delay: Int = 2,
                                  maxRetries: Int = 2,
                                  timeout: Int = 2): UUID = {
        val hm = new HealthMonitor()
        hm.setId(id)
        hm.setAdminStateUp(adminStateUp)
        hm.setDelay(delay)
        hm.setMaxRetries(maxRetries)
        hm.setTimeout(timeout)
        clusterDataClient.healthMonitorCreate(hm)
        Thread.sleep(50)
        id
    }

    override def matchHealthMonitor(id: UUID, adminStateUp: Boolean,
                                    delay: Int, timeout: Int, maxRetries: Int): Boolean = {
        val hm = clusterDataClient.healthMonitorGet(id)
        hm.isAdminStateUp == adminStateUp && hm.getDelay == delay &&
            hm.getTimeout == timeout && hm.getMaxRetries == maxRetries
    }


    override def newRandomHealthMonitor
            (id: UUID = UUID.randomUUID()): UUID = {
        val rand = new Random()
        val hm = new HealthMonitor()
        hm.setId(id)
        hm.setAdminStateUp(true)
        hm.setDelay(rand.nextInt(100) + 1)
        hm.setMaxRetries(rand.nextInt(100) + 1)
        hm.setTimeout(rand.nextInt(100) + 1)
        clusterDataClient.healthMonitorCreate(hm)
        Thread.sleep(50)
        id
    }

    override def setHealthMonitorDelay(hmId: UUID, delay: Int) = {
        val hm = clusterDataClient.healthMonitorGet(hmId)
        hm.setDelay(delay)
        clusterDataClient.healthMonitorUpdate(hm)
    }

    override def deleteHealthMonitor(hm: UUID) {
        clusterDataClient.healthMonitorDelete(hm)
    }

    override def newPool(loadBalancer: UUID,
                         id: UUID = UUID.randomUUID,
                         adminStateUp: Boolean = true,
                         lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                         hmId: UUID = null): UUID = {
        val pool = new Pool()
        pool.setLoadBalancerId(loadBalancer)
        pool.setHealthMonitorId(hmId)
        pool.setAdminStateUp(adminStateUp)
        pool.setLbMethod(lbMethod)
        pool.setLoadBalancerId(loadBalancer)
        pool.setId(id)
        clusterDataClient.poolCreate(pool)
        Thread.sleep(50)
        id
    }

    override def setPoolHealthMonitor(poolId: UUID, hmId: UUID) = {
        val pool = clusterDataClient.poolGet(poolId)
        pool.setHealthMonitorId(hmId)
        clusterDataClient.poolUpdate(pool)
    }

    override def setPoolAdminStateUp(poolId: UUID, adminStateUp: Boolean) {
        val pool = clusterDataClient.poolGet(poolId)
        pool.setAdminStateUp(adminStateUp)
        clusterDataClient.poolUpdate(pool)
    }

    override def setPoolLbMethod(poolId: UUID, lbMethod: PoolLBMethod) {
        val pool = clusterDataClient.poolGet(poolId)
        pool.setLbMethod(lbMethod)
        clusterDataClient.poolUpdate(pool)
    }

    override def setPoolMapStatus(
        pool: UUID, status: PoolHealthMonitorMappingStatus): Unit = {
        clusterDataClient.poolSetMapStatus(pool, status)
    }

    override def newPoolMember(pool: UUID, address: String, port: Int,
                               weight: Int = 1) : UUID = {
        val poolMember = new PoolMember()
        poolMember.setId(UUID.randomUUID)
        poolMember.setAdminStateUp(true)
        poolMember.setStatus(LBStatus.ACTIVE)
        poolMember.setAddress(address)
        poolMember.setProtocolPort(port)
        poolMember.setPoolId(pool)
        poolMember.setWeight(weight)
        clusterDataClient.poolMemberCreate(poolMember)
        Thread.sleep(50)
        poolMember.getId
    }

    override def updatePoolMember(poolMemberId: UUID,
                                  poolId: Option[UUID] = None,
                                  adminStateUp: Option[Boolean] = None,
                                  weight: Option[Integer] = None,
                                  status: Option[LBStatus] = None) {
        val poolMember = clusterDataClient.poolMemberGet(poolMemberId)
        poolId.foreach(poolMember.setPoolId)
        adminStateUp.foreach(poolMember.setAdminStateUp)
        weight.foreach(poolMember.setWeight(_))
        status.foreach(poolMember.setStatus)
        clusterDataClient.poolMemberUpdate(poolMember)
    }

    override def deletePoolMember(poolMember: UUID): Unit =
        clusterDataClient.poolMemberDelete(poolMember)

    import VirtualConfigurationBuilders.TraceDeviceType
    override def newTraceRequest(device: UUID,
                                 devType: TraceDeviceType.TraceDeviceType,
                                 condition: Condition,
                                 enabled: Boolean = false): UUID = {
        val trace = new TraceRequest()
            .setDeviceType(devType match {
                               case TraceDeviceType.BRIDGE => TraceRequest.DeviceType.BRIDGE
                               case TraceDeviceType.ROUTER => TraceRequest.DeviceType.ROUTER
                               case TraceDeviceType.PORT => TraceRequest.DeviceType.PORT
                           })
            .setDeviceId(device)
            .setCondition(condition)
        clusterDataClient.traceRequestCreate(trace, enabled)
    }

    override def listTraceRequests(tenant: Option[String] = None): List[UUID] = {
        tenant match {
            case Some(t) => clusterDataClient.traceRequestFindByTenant(t).
                    asScala.map( _.getId).toList
            case None => clusterDataClient.traceRequestGetAll().
                    asScala.map(_.getId).toList
        }
    }

    override def deleteTraceRequest(tr: UUID): Unit = {
        clusterDataClient.traceRequestDelete(tr)
    }
    override def enableTraceRequest(tr: UUID): Unit = {
        clusterDataClient.traceRequestEnable(tr)
    }

    override def disableTraceRequest(tr: UUID): Unit = {
        clusterDataClient.traceRequestDisable(tr)
    }

    override def isTraceRequestEnabled(tr: UUID): Boolean = {
        clusterDataClient.traceRequestGet(tr).getEnabledRule != null
    }

}
