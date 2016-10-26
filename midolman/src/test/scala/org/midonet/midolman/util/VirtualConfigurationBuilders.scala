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

import org.midonet.cluster.models.Topology.{QosPolicy, QosRuleBandwidthLimit, QosRuleDscp}
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.rules.{Condition, FragmentPolicy, NatTarget}
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus
import org.midonet.midolman.state.l4lb.{LBStatus, PoolLBMethod}
import org.midonet.packets.{IPAddr, IPv4Addr, IPv4Subnet, MAC, TCP}

object VirtualConfigurationBuilders {
    case class DhcpOpt121Route(gw: IPv4Addr, subnet: IPv4Subnet)

    object TraceDeviceType extends Enumeration {
        type TraceDeviceType = Value
        val BRIDGE, ROUTER, PORT = Value
    }
}

trait VirtualConfigurationBuilders {

    def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID
    final def newHost(name: String, id: UUID): UUID = newHost(name, id, Set.empty)
    final def newHost(name: String): UUID = newHost(name, UUID.randomUUID)
    def isHostAlive(id: UUID): Boolean
    def makeHostAlive(id: UUID): Unit
    def addHostVrnPortMapping(host: UUID, port: UUID, iface: String): Unit

    def newInboundChainOnBridge(name: String, bridge: UUID): UUID
    def newOutboundChainOnBridge(name: String, bridge: UUID): UUID
    def newInboundChainOnRouter(name: String, router: UUID): UUID
    def newOutboundChainOnRouter(name: String, router: UUID): UUID
    def newChain(name: String, id: Option[UUID] = None): UUID
    def newOutboundChainOnPort(name: String, port: UUID, id: UUID): UUID
    def newInboundChainOnPort(name: String, port: UUID, id: UUID): UUID

    final def newOutboundChainOnPort(name: String, port: UUID): UUID =
        newOutboundChainOnPort(name, port, UUID.randomUUID)
    final def newInboundChainOnPort(name: String, port: UUID): UUID =
        newInboundChainOnPort(name, port, UUID.randomUUID)

    def newLiteralRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                              action: Action): UUID
    def newTraceRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                            requestId: UUID): UUID

    final def newTcpDstRuleOnChain(
            chain: UUID, pos: Int, dstPort: Int, action: Action,
            fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED): UUID = {
        val condition = newCondition(nwProto = Some(TCP.PROTOCOL_NUMBER),
                                     tpDst = Some(dstPort),
                                     fragmentPolicy = fragmentPolicy)
        newLiteralRuleOnChain(chain, pos, condition, action)
    }

    final def newIpAddrGroupRuleOnChain(chain: UUID, pos: Int, action: Action,
                                  ipAddrGroupIdDst: Option[UUID],
                                  ipAddrGroupIdSrc: Option[UUID]): UUID = {
        val condition = newCondition(ipAddrGroupIdDst = ipAddrGroupIdDst,
                                     ipAddrGroupIdSrc = ipAddrGroupIdSrc)
        newLiteralRuleOnChain(chain, pos, condition, action)
    }

    def newForwardNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                 action: Action, targets: Set[NatTarget],
                                 isDnat: Boolean) : UUID
    def newReverseNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                         action: Action, isDnat: Boolean) : UUID
    def removeRuleFromBridge(bridge: UUID): Unit
    def newJumpRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                           jumpToChainID: UUID): UUID
    final def newFragmentRuleOnChain(chain: UUID, pos: Int,
                                        fragmentPolicy: FragmentPolicy,
                                        action: Action): UUID = {
        val condition = newCondition(fragmentPolicy = fragmentPolicy)
        newLiteralRuleOnChain(chain, pos, condition, action)
    }

    def deleteRule(id: UUID): Unit
    final def newIpAddrGroup(): UUID = newIpAddrGroup(UUID.randomUUID())
    def newIpAddrGroup(id: UUID): UUID
    def addIpAddrToIpAddrGroup(id: UUID, addr: String): Unit
    def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit
    def deleteIpAddrGroup(id: UUID): Unit
    def greTunnelZone(name: String, id: Option[UUID] = None): UUID
    def addTunnelZoneMember(tz: UUID, host: UUID, ip: IPv4Addr): Unit
    def deleteTunnelZoneMember(tz: UUID, host: UUID): Unit

    def newBridge(name: String, tenant: Option[String] = None): UUID
    def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit
    def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit
    def deleteBridge(bridge: UUID): Unit

    def newBridgePort(bridge: UUID,
                      host: Option[UUID] = None,
                      interface: Option[String] = None,
                      vlanId: Option[Int] = None,
                      qosPolicyId: Option[UUID] = None): UUID

    def setPortAdminStateUp(port: UUID, state: Boolean): Unit
    def deletePort(port: UUID): Unit
    def deletePort(port: UUID, hostId: UUID): Unit
    def newPortGroup(name: String, stateful: Boolean = false): UUID
    def setPortGroupStateful(id: UUID, stateful: Boolean): Unit
    def newPortGroupMember(pgId: UUID, portId: UUID): Unit
    def deletePortGroupMember(pgId: UUID, portId: UUID): Unit

    def newRouter(name: String): UUID
    def setRouterAdminStateUp(router: UUID, state: Boolean): Unit
    def deleteRouter(router: UUID): Unit

    def newRouterPort(router: UUID, mac: MAC, portAddr: String,
                      nwAddr: String, nwLen: Int,
                      vni: Int = 0, tunnelIp: Option[IPv4Addr] = None,
                      containerId: Option[UUID] = None): UUID

    final def newRouterPort(routerId: UUID, mac: MAC, portAddr: IPv4Subnet): UUID =
        newRouterPort(routerId, mac, portAddr.toUnicastString,
            portAddr.toNetworkAddress.toString, portAddr.getPrefixLen)

    final def newL2RouterPort(router: UUID, mac: MAC, portAddr: IPv4Subnet,
                              vni: Int, tunnelIp: IPv4Addr) =
        newRouterPort(router, mac, portAddr.toUnicastString,
            portAddr.toNetworkAddress.toString, portAddr.getPrefixLen,
            vni, Some(tunnelIp))

    def newVxLanPort(bridge: UUID, mgmtIp: IPv4Addr, mgmtPort: Int,
                     vni: Int, tunnelIp: IPv4Addr, tunnelZone: UUID): UUID

    def newRoute(router: UUID,
                 srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                 nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                 weight: Int): UUID
    def deleteRoute(routeId: UUID): Unit

    def addDhcpSubnet(bridge: UUID,
                      subnet: IPv4Subnet,
                      enabled: Boolean,
                      gw: IPv4Addr,
                      dns: List[IPv4Addr],
                      mtu: Short,
                      opt121routes: List[VirtualConfigurationBuilders.DhcpOpt121Route]): IPv4Subnet
    def addDhcpHost(bridge: UUID, subnet: IPv4Subnet,
                    hostMac: MAC, hostIp: IPv4Addr): MAC
    def setDhcpHostOptions(bridge: UUID, subnet: IPv4Subnet,
                           host: MAC, options: Map[String, String]): Unit

    def linkPorts(port: UUID, peerPort: UUID): Unit
    def unlinkPorts(port: UUID): Unit
    def materializePort(port: UUID, hostId: UUID, portName: String): Unit
    final def newCondition(
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

    def newLoadBalancer(id: UUID = UUID.randomUUID): UUID
    def deleteLoadBalancer(id: UUID): Unit
    def setLoadBalancerOnRouter(loadBalancer: UUID, router: UUID): Unit
    def setLoadBalancerDown(loadBalancer: UUID): Unit

    final def newVip(pool: UUID): UUID = newVip(pool, "10.10.10.10", 10)
    def newVip(pool: UUID, address: String, port: Int): UUID

    def deleteVip(vip: UUID): Unit
    def matchVip(vip: UUID, address: IPAddr, protocolPort: Int): Boolean

    def newRandomVip(pool: UUID): UUID

    def setVipAdminStateUp(vip: UUID, adminStateUp: Boolean): Unit
    def vipEnableStickySourceIP(vip: UUID): Unit
    def vipDisableStickySourceIP(vip: UUID): Unit
    def newHealthMonitor(id: UUID = UUID.randomUUID(),
                           adminStateUp: Boolean = true,
                           delay: Int = 2,
                           maxRetries: Int = 2,
                         timeout: Int = 2): UUID
    def matchHealthMonitor(id: UUID, adminStateUp: Boolean,
                           delay: Int, timeout: Int, maxRetries: Int): Boolean
    def newRandomHealthMonitor(id: UUID = UUID.randomUUID()): UUID
    def setHealthMonitorDelay(hm: UUID, delay: Int): Unit
    def deleteHealthMonitor(hm: UUID): Unit
    def newPool(loadBalancer: UUID,
                id: UUID = UUID.randomUUID,
                adminStateUp: Boolean = true,
                lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                hmId: UUID = null): UUID
    def setPoolHealthMonitor(pool: UUID, hmId: UUID): Unit
    def setPoolAdminStateUp(pool: UUID, adminStateUp: Boolean): Unit
    def setPoolLbMethod(pool: UUID, lbMethod: PoolLBMethod): Unit
    def setPoolMapStatus(pool: UUID, status: PoolHealthMonitorMappingStatus): Unit

    final def newPoolMember(pool: UUID): UUID = newPoolMember(pool, "10.10.10.10", 10)
    def newPoolMember(pool: UUID, address: String, port: Int,
                         weight: Int = 1): UUID
    def updatePoolMember(poolMember: UUID,
                         poolId: Option[UUID] = None,
                         adminStateUp: Option[Boolean] = None,
                         weight: Option[Integer] = None,
                         status: Option[LBStatus] = None): Unit
    def deletePoolMember(poolMember: UUID): Unit

    final def setPoolMemberAdminStateUp(poolMember: UUID,
                                        adminStateUp: Boolean) =
        updatePoolMember(poolMember, adminStateUp = Some(adminStateUp))

    final def setPoolMemberHealth(poolMember: UUID,
                                     status: LBStatus) =
        updatePoolMember(poolMember, status = Some(status))

    import VirtualConfigurationBuilders.TraceDeviceType
    def newTraceRequest(device: UUID,
                        devType: TraceDeviceType.TraceDeviceType,
                        condition: Condition,
                        enabled: Boolean = false): UUID
    def listTraceRequests(tenant: Option[String] = None): List[UUID]
    def deleteTraceRequest(tr: UUID): Unit
    def enableTraceRequest(tr: UUID): Unit
    def disableTraceRequest(tr: UUID): Unit
    def isTraceRequestEnabled(tr: UUID): Boolean

    def newQosPolicy(): UUID
    def newQosBWLimitRule(policyId: UUID,
                          maxKbps: Int,
                          maxBurstKbps: Option[Int]): UUID
    def newQosDscpRule(policyId: UUID,
                       dscpMark: Int): UUID

    def newServiceContainer(): UUID
}

trait ForwardingVirtualConfigurationBuilders
        extends VirtualConfigurationBuilders {

    def virtConfBuilderImpl: VirtualConfigurationBuilders

    override def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID =
        virtConfBuilderImpl.newHost(name, id, tunnelZones)
    override def isHostAlive(id: UUID): Boolean =
        virtConfBuilderImpl.isHostAlive(id)
    override def makeHostAlive(id: UUID): Unit =
        virtConfBuilderImpl.makeHostAlive(id)

    override def addHostVrnPortMapping(host: UUID, port: UUID, iface: String): Unit =
        virtConfBuilderImpl.addHostVrnPortMapping(host, port, iface)

    override def newInboundChainOnBridge(name: String, bridge: UUID): UUID =
        virtConfBuilderImpl.newInboundChainOnBridge(name, bridge)
    override def newOutboundChainOnBridge(name: String, bridge: UUID): UUID =
        virtConfBuilderImpl.newOutboundChainOnBridge(name, bridge)
    override def newInboundChainOnRouter(name: String, router: UUID): UUID =
        virtConfBuilderImpl.newInboundChainOnRouter(name, router)
    override def newOutboundChainOnRouter(name: String, router: UUID): UUID =
        virtConfBuilderImpl.newOutboundChainOnRouter(name, router)
    override def newChain(name: String, id: Option[UUID] = None): UUID =
        virtConfBuilderImpl.newChain(name, id)
    override def newOutboundChainOnPort(name: String, port: UUID, id: UUID): UUID =
        virtConfBuilderImpl.newOutboundChainOnPort(name, port, id)
    override def newInboundChainOnPort(name: String, port: UUID, id: UUID): UUID =
        virtConfBuilderImpl.newInboundChainOnPort(name, port, id)
    override def newLiteralRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                       action: Action): UUID =
        virtConfBuilderImpl.newLiteralRuleOnChain(chain, pos, condition, action)
    override def newTraceRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                     requestId: UUID): UUID =
        virtConfBuilderImpl.newTraceRuleOnChain(chain, pos, condition, requestId)

    override def newForwardNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                          action: Action, targets: Set[NatTarget],
                                          isDnat: Boolean) : UUID =
        virtConfBuilderImpl.newForwardNatRuleOnChain(chain, pos, condition, action, targets, isDnat)
    override def newReverseNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                          action: Action, isDnat: Boolean) : UUID =
        virtConfBuilderImpl.newReverseNatRuleOnChain(chain, pos, condition, action, isDnat)
    override def removeRuleFromBridge(bridge: UUID): Unit =
        virtConfBuilderImpl.removeRuleFromBridge(bridge)
    override def newJumpRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                    jumpToChainID: UUID): UUID =
        virtConfBuilderImpl.newJumpRuleOnChain(chain, pos, condition, jumpToChainID)

    override def deleteRule(id: UUID): Unit = virtConfBuilderImpl.deleteRule(id)

    override def newIpAddrGroup(id: UUID): UUID = virtConfBuilderImpl.newIpAddrGroup(id)
    override def addIpAddrToIpAddrGroup(id: UUID, addr: String): Unit = virtConfBuilderImpl.addIpAddrToIpAddrGroup(id, addr)
    override def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit =
        virtConfBuilderImpl.removeIpAddrFromIpAddrGroup(id, addr)
    override def deleteIpAddrGroup(id: UUID): Unit = virtConfBuilderImpl.deleteIpAddrGroup(id)
    override def greTunnelZone(name: String, id: Option[UUID] = None): UUID =
        virtConfBuilderImpl.greTunnelZone(name, id)
    override def addTunnelZoneMember(tz: UUID, host: UUID, ip: IPv4Addr): Unit =
        virtConfBuilderImpl.addTunnelZoneMember(tz, host, ip)
    override def deleteTunnelZoneMember(tz: UUID, host: UUID): Unit =
        virtConfBuilderImpl.deleteTunnelZoneMember(tz, host)

    override def newBridge(name: String, tenant: Option[String] = None): UUID =
        virtConfBuilderImpl.newBridge(name, tenant)
    override def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit =
        virtConfBuilderImpl.setBridgeAdminStateUp(bridge, state)
    override def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit =
        virtConfBuilderImpl.feedBridgeIp4Mac(bridge, ip, mac)
    override def deleteBridge(bridge: UUID): Unit =
        virtConfBuilderImpl.deleteBridge(bridge)

    override def newBridgePort(bridge: UUID,
                               host: Option[UUID] = None,
                               interface: Option[String] = None,
                               vlanId: Option[Int] = None,
                               qosPolicyId: Option[UUID] = None): UUID =
        virtConfBuilderImpl.newBridgePort(bridge, host, interface, vlanId,
                                          qosPolicyId)

    override def setPortAdminStateUp(port: UUID, state: Boolean): Unit =
        virtConfBuilderImpl.setPortAdminStateUp(port, state)

    override def deletePort(port: UUID): Unit =
        virtConfBuilderImpl.deletePort(port)
    override def deletePort(port: UUID, hostId: UUID): Unit =
        virtConfBuilderImpl.deletePort(port, hostId)
    override def newPortGroup(name: String, stateful: Boolean = false): UUID =
        virtConfBuilderImpl.newPortGroup(name, stateful)
    override def setPortGroupStateful(id: UUID, stateful: Boolean): Unit =
        virtConfBuilderImpl.setPortGroupStateful(id, stateful)
    override def newPortGroupMember(pgId: UUID, portId: UUID): Unit =
        virtConfBuilderImpl.newPortGroupMember(pgId, portId)
    override def deletePortGroupMember(pgId: UUID, portId: UUID): Unit =
        virtConfBuilderImpl.deletePortGroupMember(pgId, portId)

    override def newRouter(name: String): UUID =
        virtConfBuilderImpl.newRouter(name)
    override def setRouterAdminStateUp(router: UUID, state: Boolean): Unit =
        virtConfBuilderImpl.setRouterAdminStateUp(router, state)
    override def deleteRouter(router: UUID): Unit =
        virtConfBuilderImpl.deleteRouter(router)

    override def newRouterPort(router: UUID, mac: MAC, portAddr: String,
                               nwAddr: String, nwLen: Int,
                               vni: Int, tunnelIp: Option[IPv4Addr],
                               containerId: Option[UUID]): UUID =
        virtConfBuilderImpl.newRouterPort(router, mac, portAddr, nwAddr, nwLen,
                                          vni, tunnelIp, containerId)

    def newVxLanPort(bridge: UUID, mgmtIp: IPv4Addr, mgmtPort: Int,
                     vni: Int, tunnelIp: IPv4Addr, tunnelZone: UUID): UUID =
        virtConfBuilderImpl.newVxLanPort(bridge, mgmtIp, mgmtPort,
                                         vni, tunnelIp, tunnelZone)

    override def newRoute(router: UUID,
                          srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                          nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                          weight: Int): UUID =
        virtConfBuilderImpl.newRoute(router, srcNw, srcNwLen, dstNw, dstNwLen,
                       nextHop, nextHopPort, nextHopGateway, weight)
    override def deleteRoute(routeId: UUID): Unit =
        virtConfBuilderImpl.deleteRoute(routeId)

    override def addDhcpSubnet(bridge: UUID,
                               subnet: IPv4Subnet,
                               enabled: Boolean,
                               gw: IPv4Addr,
                               dns: List[IPv4Addr],
                               mtu: Short,
                               opt121routes: List[VirtualConfigurationBuilders.DhcpOpt121Route]): IPv4Subnet =
        virtConfBuilderImpl.addDhcpSubnet(bridge, subnet, enabled, gw, dns, mtu,
                                          opt121routes)
    override def addDhcpHost(bridge: UUID, subnet: IPv4Subnet,
                             hostMac: MAC, hostIp: IPv4Addr): MAC =
        virtConfBuilderImpl.addDhcpHost(bridge, subnet, hostMac, hostIp)
    override def setDhcpHostOptions(bridge: UUID,
                                    subnet: IPv4Subnet, host: MAC,
                                    options: Map[String, String]): Unit =
        virtConfBuilderImpl.setDhcpHostOptions(bridge, subnet, host, options)

    override def linkPorts(port: UUID, peerPort: UUID): Unit =
        virtConfBuilderImpl.linkPorts(port, peerPort)
    override def unlinkPorts(port: UUID): Unit =
        virtConfBuilderImpl.unlinkPorts(port)

    override def materializePort(port: UUID, hostId: UUID, portName: String): Unit =
        virtConfBuilderImpl.materializePort(port, hostId, portName)
    override def newLoadBalancer(id: UUID = UUID.randomUUID): UUID =
        virtConfBuilderImpl.newLoadBalancer(id)
    override def deleteLoadBalancer(id: UUID): Unit =
        virtConfBuilderImpl.deleteLoadBalancer(id)
    override def setLoadBalancerOnRouter(loadBalancer: UUID, router: UUID): Unit =
        virtConfBuilderImpl.setLoadBalancerOnRouter(loadBalancer, router)
    override def setLoadBalancerDown(loadBalancer: UUID): Unit =
        virtConfBuilderImpl.setLoadBalancerDown(loadBalancer)
    override def newVip(pool: UUID, address: String, port: Int): UUID =
        virtConfBuilderImpl.newVip(pool, address, port)
    override def deleteVip(vip: UUID): Unit =
        virtConfBuilderImpl.deleteVip(vip)
    override def matchVip(vip: UUID, address: IPAddr, protocolPort: Int): Boolean =
        virtConfBuilderImpl.matchVip(vip, address, protocolPort)

    override def newRandomVip(pool: UUID): UUID =
        virtConfBuilderImpl.newRandomVip(pool)

    override def setVipAdminStateUp(vip: UUID, adminStateUp: Boolean): Unit =
        virtConfBuilderImpl.setVipAdminStateUp(vip, adminStateUp)
    override def vipEnableStickySourceIP(vip: UUID): Unit =
        virtConfBuilderImpl.vipEnableStickySourceIP(vip)
    override def vipDisableStickySourceIP(vip: UUID): Unit =
        virtConfBuilderImpl.vipDisableStickySourceIP(vip)
    override def newHealthMonitor(id: UUID = UUID.randomUUID(),
                                  adminStateUp: Boolean = true,
                                  delay: Int = 2,
                                  maxRetries: Int = 2,
                                  timeout: Int = 2): UUID =
        virtConfBuilderImpl.newHealthMonitor(id, adminStateUp, delay, maxRetries, timeout)
    override def matchHealthMonitor(id: UUID, adminStateUp: Boolean,
                                    delay: Int, timeout: Int, maxRetries: Int): Boolean =
        virtConfBuilderImpl.matchHealthMonitor(id, adminStateUp, delay,
                                               timeout, maxRetries)
    override def newRandomHealthMonitor
        (id: UUID = UUID.randomUUID()): UUID =
        virtConfBuilderImpl.newRandomHealthMonitor(id)
    override def setHealthMonitorDelay(hm: UUID, delay: Int): Unit =
        virtConfBuilderImpl.setHealthMonitorDelay(hm, delay)
    override def deleteHealthMonitor(hm: UUID): Unit =
        virtConfBuilderImpl.deleteHealthMonitor(hm)
    override def newPool(loadBalancer: UUID,
                         id: UUID = UUID.randomUUID,
                         adminStateUp: Boolean = true,
                         lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                         hmId: UUID = null): UUID =
        virtConfBuilderImpl.newPool(loadBalancer, id, adminStateUp, lbMethod, hmId)
    override def setPoolHealthMonitor(pool: UUID, hmId: UUID): Unit =
        virtConfBuilderImpl.setPoolHealthMonitor(pool, hmId)
    override def setPoolAdminStateUp(pool: UUID, adminStateUp: Boolean): Unit =
        virtConfBuilderImpl.setPoolAdminStateUp(pool, adminStateUp)
    override def setPoolLbMethod(pool: UUID, lbMethod: PoolLBMethod): Unit =
        virtConfBuilderImpl.setPoolLbMethod(pool, lbMethod)
    override def setPoolMapStatus(pool: UUID, status: PoolHealthMonitorMappingStatus): Unit =
        virtConfBuilderImpl.setPoolMapStatus(pool, status)
    override def newPoolMember(pool: UUID, address: String, port: Int,
                               weight: Int = 1): UUID =
        virtConfBuilderImpl.newPoolMember(pool, address, port, weight)
    override def updatePoolMember(poolMember: UUID,
                                  poolId: Option[UUID] = None,
                                  adminStateUp: Option[Boolean] = None,
                                  weight: Option[Integer] = None,
                                  status: Option[LBStatus] = None): Unit =
        virtConfBuilderImpl.updatePoolMember(poolMember, poolId, adminStateUp,
                               weight, status)
    override def deletePoolMember(poolMember: UUID): Unit =
        virtConfBuilderImpl.deletePoolMember(poolMember)

    import VirtualConfigurationBuilders.TraceDeviceType
    override def newTraceRequest(device: UUID,
                                 devType: TraceDeviceType.TraceDeviceType,
                                 condition: Condition,
                                 enabled: Boolean = false): UUID =
        virtConfBuilderImpl.newTraceRequest(device, devType, condition, enabled)
    override def listTraceRequests(tenant: Option[String] = None): List[UUID] =
        virtConfBuilderImpl.listTraceRequests(tenant)
    override def deleteTraceRequest(tr: UUID): Unit =
        virtConfBuilderImpl.deleteTraceRequest(tr)
    override def enableTraceRequest(tr: UUID): Unit =
        virtConfBuilderImpl.enableTraceRequest(tr)
    override def disableTraceRequest(tr: UUID): Unit =
        virtConfBuilderImpl.disableTraceRequest(tr)
    override def isTraceRequestEnabled(tr: UUID): Boolean =
        virtConfBuilderImpl.isTraceRequestEnabled(tr)

    override def newQosPolicy(): UUID =
        virtConfBuilderImpl.newQosPolicy()
    override def newQosBWLimitRule(policyId: UUID,
                                   maxKbps: Int,
                                   maxBurstKbps: Option[Int]): UUID =
        virtConfBuilderImpl.newQosBWLimitRule(policyId, maxKbps, maxBurstKbps)
    override def newQosDscpRule(policyId: UUID,
                                dscpMark: Int): UUID =
        virtConfBuilderImpl.newQosDscpRule(policyId, dscpMark)

    override def newServiceContainer(): UUID =
        virtConfBuilderImpl.newServiceContainer()
}
