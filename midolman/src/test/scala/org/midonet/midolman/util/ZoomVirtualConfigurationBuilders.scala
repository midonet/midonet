/*
 * Copyright 2015 Midokura SARL
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

import java.util.concurrent.atomic.AtomicLong
import java.util.{ArrayList, Random, UUID}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.inject.Inject

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.Condition
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.models.Topology.Rule.{Action, NatTarget, TraceRuleData}
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.services.MidonetBackend

import org.midonet.cluster.state.PortStateStorage._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus
import org.midonet.midolman.state.l4lb.{LBStatus, PoolLBMethod}
import org.midonet.packets.{IPAddr, IPSubnet, IPv4Addr, IPv4Subnet, MAC}
import org.midonet.util.concurrent._

class ZoomVirtualConfigurationBuilders @Inject()(backend: MidonetBackend)
        extends VirtualConfigurationBuilders
        with TopologyBuilder {
    val tunnelKeys = new AtomicLong(0)

    val store = backend.store
    val stateStore = backend.stateStore

    val awaitTimeout = 5 seconds

    override def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID = {
        store.create(createHost(id, tunnelZoneIds=tunnelZones))
        id
    }

    // don't implement, only used by VirtualToPhysicalMapperTest which is legacy only
    override def isHostAlive(id: UUID): Boolean = ???
    override def makeHostAlive(id: UUID): Unit = {
        stateStore.addValue(classOf[Host], id,
                            MidonetBackend.AliveKey,
                            MidonetBackend.AliveKey).toBlocking.first
    }

    override def addHostVrnPortMapping(host: UUID, port: UUID, iface: String): Unit = {
        val p = Await.result(store.get(classOf[Port], port), awaitTimeout)
        store.update(p.toBuilder
                         .setHostId(host.asProto)
                         .setInterfaceName(iface)
                         .build())
    }

    override def newInboundChainOnBridge(name: String, bridge: UUID): UUID = {
        val chain = newChain(name)
        val n = Await.result(store.get(classOf[Network], bridge), awaitTimeout)
        store.update(n.toBuilder
                         .setInboundFilterId(chain.asProto)
                         .build())
        chain
    }
    override def newOutboundChainOnBridge(name: String, bridge: UUID): UUID = {
        val chain = newChain(name)
        val n = Await.result(store.get(classOf[Network], bridge), awaitTimeout)
        store.update(n.toBuilder
                         .setOutboundFilterId(chain.asProto)
                         .build())
        chain
    }
    override def newInboundChainOnRouter(name: String, router: UUID): UUID = {
        val chain = newChain(name)
        val r = Await.result(store.get(classOf[Router], router), awaitTimeout)
        store.update(r.toBuilder
                         .setInboundFilterId(chain.asProto)
                         .build())
        chain
    }
    override def newOutboundChainOnRouter(name: String, router: UUID): UUID =  {
        val chain = newChain(name)
        val r = Await.result(store.get(classOf[Router], router), awaitTimeout)
        store.update(r.toBuilder
                         .setOutboundFilterId(chain.asProto)
                         .build())
        chain
    }

    override def newChain(name: String, id: Option[UUID] = None): UUID = {
        val chain = id.getOrElse(UUID.randomUUID)
        store.create(createChain(chain, Some(name)))
        chain
    }

    override def newOutboundChainOnPort(name: String, port: UUID, id: UUID): UUID = {
        val chain = newChain(name)
        val p = Await.result(store.get(classOf[Port], port), awaitTimeout)
        store.update(p.toBuilder
                         .setOutboundFilterId(chain.asProto)
                         .build())
        chain
    }

    override def newInboundChainOnPort(name: String, port: UUID, id: UUID): UUID = {
        val chain = newChain(name)
        val p = Await.result(store.get(classOf[Port], port), awaitTimeout)
        store.update(p.toBuilder
                         .setInboundFilterId(chain.asProto)
                         .build())
        chain
    }

    def insertRule(c: Chain, pos: Int, rule: UUID): Chain = {
        val rules = new ArrayList[Commons.UUID]
        rules.addAll(c.getRuleIdsList())
        rules.add(pos-1, rule.asProto)
        c.toBuilder
            .clearRuleIds()
            .addAllRuleIds(rules)
            .build()
    }

    override def newLiteralRuleOnChain(chain: UUID, pos: Int, condition: rules.Condition,
                                       action: RuleResult.Action): UUID = {
        val rule = UUID.randomUUID
        val builder = createLiteralRuleBuilder(rule, None, Some(action))
        builder.setCondition(setConditionFromCondition(Condition.newBuilder,
                                                       condition))
        store.create(builder.build())
        val c = Await.result(store.get(classOf[Chain], chain), awaitTimeout)
        store.update(insertRule(c, pos, rule))
        rule
    }

    override def newTraceRuleOnChain(chain: UUID, pos: Int,
                                     condition: rules.Condition,
                                     requestId: UUID): UUID = {
        val rule = UUID.randomUUID
        val builder = createTraceRuleBuilder(rule)
        val trd = TraceRuleData.newBuilder()
                               .setTraceRequestId(UUIDUtil.toProto(requestId))
                               .setLimit(Long.MaxValue)
                               .build()
        builder.setTraceRuleData(trd)

        builder.setCondition(setConditionFromCondition(Condition.newBuilder,
                                                       condition))
        store.create(builder.build())
        val c = Await.result(store.get(classOf[Chain], chain), awaitTimeout)
        store.update(insertRule(c, pos, rule))
        rule
    }

    override def newForwardNatRuleOnChain(chain: UUID, pos: Int,
                                          condition: rules.Condition,
                                          action: RuleResult.Action, targets: Set[rules.NatTarget],
                                          isDnat: Boolean) : UUID = {
        val id = UUID.randomUUID
        val builder = createNatRuleBuilder(id, None, Option(isDnat),
                                           None, targets)
        builder.setCondition(setConditionFromCondition(Condition.newBuilder,
                                                       condition))
        store.create(builder.build())
        val c = Await.result(store.get(classOf[Chain], chain), awaitTimeout)
        store.update(insertRule(c, pos, id))
        id
    }

    override def newReverseNatRuleOnChain(chain: UUID, pos: Int,
                                          condition: rules.Condition,
                                          action: RuleResult.Action, isDnat: Boolean) : UUID = {
        val id = UUID.randomUUID
        val builder = createNatRuleBuilder(id, None, Option(isDnat),
                                           None, reverse=true)
        builder.setCondition(setConditionFromCondition(Condition.newBuilder,
                                                       condition))
        store.create(builder.build())
        val c = Await.result(store.get(classOf[Chain], chain), awaitTimeout)
        store.update(insertRule(c, pos, id))
        id
    }

    override def removeRuleFromBridge(bridge: UUID): Unit = {
        val b = Await.result(store.get(classOf[Network], bridge), awaitTimeout)
        store.update(b.toBuilder.clearInboundFilterId().build())
    }

    override def newJumpRuleOnChain(chain: UUID, pos: Int,
                                    condition: rules.Condition,
                                    jumpToChainID: UUID): UUID = {
        val id = UUID.randomUUID
        val builder = createJumpRuleBuilder(id, None, Some(jumpToChainID))
        builder.setCondition(setConditionFromCondition(Condition.newBuilder,
                                                       condition))
        store.create(builder.build())
        val c = Await.result(store.get(classOf[Chain], chain), awaitTimeout)
        store.update(insertRule(c, pos, id))
        id
    }

    override def deleteRule(id: UUID): Unit = {
        store.delete(classOf[Rule], id)
    }

    override def newIpAddrGroup(id: UUID): UUID = {
        store.create(createIpAddrGroup(id))
        id
    }

    override def addIpAddrToIpAddrGroup(id: UUID, addr: String): Unit = {
        val g = Await.result(store.get(classOf[IPAddrGroup], id), awaitTimeout)
        store.update(g.toBuilder
                         .addIpAddrPorts(IPAddrGroup.IPAddrPorts.newBuilder()
                                             .setIpAddress(IPv4Addr(addr).asProto)
                                             .build())
                         .build())
    }

    override def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit = {
        val g = Await.result(store.get(classOf[IPAddrGroup], id), awaitTimeout)
        val ports = g.getIpAddrPortsList.asScala
            .filter(_.getIpAddress != IPv4Addr(addr).asProto)
            .asJava
        store.update(g.toBuilder
                         .clearIpAddrPorts()
                         .addAllIpAddrPorts(ports)
                         .build())
    }

    override def deleteIpAddrGroup(id: UUID): Unit = {
        store.delete(classOf[IPAddrGroup], id)
    }

    override def greTunnelZone(name: String, id: Option[UUID] = None): UUID = {
        val idToUse = id.getOrElse(UUID.randomUUID)
        store.create(createTunnelZone(idToUse, TunnelZone.Type.GRE,
                                      Some(name), Map[UUID, IPAddr]()))
        idToUse
    }

    override def addTunnelZoneMember(tz: UUID, host: UUID, ip: IPv4Addr): Unit = {
        val tzone = Await.result(store.get(classOf[TunnelZone], tz), awaitTimeout)
        store.update(tzone.toBuilder
                         .addHosts(HostToIp.newBuilder()
                                       .setHostId(host.asProto)
                                       .setIp(ip.asProto).build())
                         .addHostIds(host.asProto)
                         .build())
    }

    override def deleteTunnelZoneMember(tz: UUID, host: UUID): Unit = {
        val tzone = Await.result(store.get(classOf[TunnelZone], tz), awaitTimeout)
        val hosts = tzone.getHostsList().asScala
            .filter(_.getHostId != host.asProto)
            .asJava
        val hostIds = tzone.getHostIdsList().asScala
            .filter(_ != host.asProto)
            .asJava

        store.update(tzone.toBuilder
                         .clearHosts()
                         .clearHostIds()
                         .addAllHosts(hosts)
                         .addAllHostIds(hostIds)
                         .build())
    }

    override def newBridge(name: String, tenant: Option[String] = None): UUID = {
        val id = UUID.randomUUID
        store.create(createBridge(id, tenant, Some(name), adminStateUp=true))
        id
    }
    override def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit = {
        val n = Await.result(store.get(classOf[Network], bridge), awaitTimeout)
        store.update(n.toBuilder
                         .setAdminStateUp(state)
                         .build())
    }

    override def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit = {
        val map = backend.stateTableStore.bridgeArpTable(bridge)
        map.addPersistent(ip, mac).await()
    }

    override def deleteBridge(bridge: UUID): Unit = {
        store.delete(classOf[Network], bridge)
    }

    override def newBridgePort(bridge: UUID,
                               host: Option[UUID] = None,
                               interface: Option[String] = None,
                               vlanId: Option[Int] = None,
                               qosPolicyId: Option[UUID] = None): UUID = {
        val id = UUID.randomUUID
        store.create(createBridgePort(id, bridgeId=Some(bridge),
                                      tunnelKey=tunnelKeys.incrementAndGet(),
                                      hostId=host, interfaceName=interface,
                                      adminStateUp=true, vlanId = vlanId,
                                      qosPolicyId = qosPolicyId))
        id
    }

    override def setPortAdminStateUp(port: UUID, state: Boolean): Unit = {
        val p = Await.result(store.get(classOf[Port], port), awaitTimeout)
        store.update(p.toBuilder
                         .setAdminStateUp(state)
                         .build())
    }

    override def deletePort(port: UUID): Unit = {
        store.delete(classOf[Port], port)
    }

    override def deletePort(port: UUID, hostId: UUID): Unit = {
        store.delete(classOf[Port], port)
    }

    override def newPortGroup(name: String, stateful: Boolean = false): UUID = {
        val id = UUID.randomUUID
        store.create(createPortGroup(id, Some(name), stateful=Some(stateful)))
        id
    }

    override def setPortGroupStateful(id: UUID, stateful: Boolean): Unit = {
        val pg = Await.result(store.get(classOf[PortGroup], id), awaitTimeout)
        store.update(pg.toBuilder.setStateful(stateful).build())
    }

    override def newPortGroupMember(pgId: UUID, portId: UUID): Unit = {
        val pg = Await.result(store.get(classOf[PortGroup], pgId), awaitTimeout)
        store.update(pg.toBuilder
                         .addPortIds(portId.asProto).build())
    }

    override def deletePortGroupMember(pgId: UUID, portId: UUID): Unit = {
        val pg = Await.result(store.get(classOf[PortGroup], pgId), awaitTimeout)
        store.update(pg.toBuilder
                         .clearPortIds()
                         .addAllPortIds(pg.getPortIdsList().asScala
                                            .filter({ _ != portId.asProto }).asJava)
                         .build())
    }

    override def newRouter(name: String): UUID = {
        val id  = UUID.randomUUID
        store.create(createRouter(id, name=Some(name), adminStateUp=true))
        id
    }
    override def setRouterAdminStateUp(router: UUID, state: Boolean): Unit = {
        val r = Await.result(store.get(classOf[Router], router), awaitTimeout)
        store.update(r.toBuilder
                         .setAdminStateUp(state)
                         .build())
    }

    override def deleteRouter(router: UUID): Unit = {
        store.delete(classOf[Router], router)
    }

    override def newRouterPort(router: UUID, mac: MAC, portAddr: String,
                               nwAddr: String, nwLen: Int,
                               vni: Int, tunnelIp: Option[IPv4Addr],
                               containerId: Option[UUID]): UUID = {
        val id = UUID.randomUUID
        val addr = IPv4Addr.fromString(portAddr)
        store.create(createRouterPort(id, routerId=Some(router),
                                      tunnelKey=tunnelKeys.incrementAndGet(),
                                      portMac=mac,
                                      portAddress=addr,
                                      portSubnet=toSubnet(nwAddr, nwLen),
                                      adminStateUp=true,
                                      vni = vni,
                                      tunnelIp = tunnelIp,
                                      containerId = containerId))

        store.create(createRoute(srcNetwork=new IPv4Subnet(0,0),
                                 dstNetwork=new IPv4Subnet(addr, 32),
                                 nextHop=NextHop.LOCAL,
                                 nextHopPortId=Some(id),
                                 nextHopGateway=None,
                                 weight=Some(0)))
        id
    }

    def newVxLanPort(bridge: UUID, mgmtIp: IPv4Addr, mgmtPort: Int,
                     vni: Int, tunnelIp: IPv4Addr, tunnelZone: UUID): UUID = {
        val id = UUID.randomUUID
        val vtepId = UUID.randomUUID
        store.create(Vtep.newBuilder().setId(vtepId.asProto)
                        .setManagementIp(mgmtIp.asProto)
                        .setManagementPort(mgmtPort)
                        .setTunnelZoneId(tunnelZone.asProto).build())
        store.create(createVxLanPort(id, bridgeId=Some(bridge),
                                     tunnelKey=vni))
        id
    }

    override def newRoute(router: UUID,
                          srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                          nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                          weight: Int): UUID = {
        val id = UUID.randomUUID
        val routerId = nextHop match {
            case NextHop.PORT => None
            case _ => Some(router)
        }
        store.create(createRoute(id, toSubnet(srcNw, srcNwLen),
                                 toSubnet(dstNw, dstNwLen), nextHop,
                                 Option(nextHopPort), Option(nextHopGateway),
                                 Option(weight), routerId=routerId))
        id
    }

    override def deleteRoute(routeId: UUID): Unit = {
        store.delete(classOf[Route], routeId)
    }

    val subnet2Id = mutable.Map[IPv4Subnet,UUID]()
    override def addDhcpSubnet(bridge: UUID,
                               subnet: IPv4Subnet,
                               enabled: Boolean,
                               gw: IPv4Addr,
                               dns: List[IPv4Addr],
                               mtu: Short,
                               opt121routes: List[VirtualConfigurationBuilders.DhcpOpt121Route]): IPv4Subnet = {
        val id = UUID.randomUUID
        val dhcp = createDhcp(networkId = bridge,
                              id = id,
                              defaultGw = gw,
                              serverAddr = gw,
                              subnetAddr = subnet,
                              dns = dns,
                              mtu = mtu,
                              enabled = enabled,
                              opt121routes = opt121routes.map(
                                  o => {
                                      Dhcp.Opt121Route.newBuilder()
                                          .setDstSubnet(o.subnet.asProto)
                                          .setGateway(o.gw.asProto)
                                          .build()
                                  }))
        store.create(dhcp)
        subnet2Id += (subnet -> id)
        subnet
    }

    val mac2id = mutable.Map[MAC,UUID]()
    override def addDhcpHost(bridge: UUID, subnet: IPv4Subnet,
                             hostMac: MAC, hostIp: IPv4Addr): MAC = {
        val id = UUID.randomUUID
        val dhcpId = subnet2Id.get(subnet).get
        val dhcp = Await.result(store.get(classOf[Dhcp], dhcpId), awaitTimeout)
        store.update(dhcp.toBuilder
                         .addHosts(Dhcp.Host.newBuilder()
                                       .setMac(hostMac.toString)
                                       .setIpAddress(hostIp.asProto)
                                       .setName("host"+hostIp.toString
                                                    .replace(".", "_"))
                                       .build())
                         .build())
        mac2id += (hostMac -> id)
        hostMac
    }

    override def setDhcpHostOptions(bridge: UUID,
                                    subnet: IPv4Subnet, host: MAC,
                                    options: Map[String, String]): Unit = {
        val dhcpId = subnet2Id.get(subnet).get
        val dhcp = Await.result(store.get(classOf[Dhcp], dhcpId), awaitTimeout)
        val hostIdx = dhcp.getHostsList.asScala.indexWhere(_.getMac == host.toString)
        if (hostIdx < -1)
            throw new IllegalArgumentException(s"No such host $host")

        val extraOpts = options map { case (name, value) =>
            Dhcp.Host.ExtraDhcpOpt.newBuilder()
                .setName(name)
                .setValue(value)
                .build()
        }
        val dhcpHost = dhcp.getHosts(hostIdx).toBuilder
            .addAllExtraDhcpOpts(extraOpts.asJava).build()
        store.update(dhcp.toBuilder.setHosts(hostIdx, dhcpHost).build())
    }

    override def linkPorts(port: UUID, peerPort: UUID): Unit = {
        val p = Await.result(store.get(classOf[Port], port), awaitTimeout)
        store.update(p.toBuilder
                         .setPeerId(peerPort.asProto)
                         .build())
    }

    override def unlinkPorts(port: UUID): Unit = {
        val p = Await.result(store.get(classOf[Port], port), awaitTimeout)
        store.update(p.toBuilder
                         .clearPeerId()
                         .build())
    }

    override def materializePort(port: UUID, hostId: UUID, portName: String): Unit = {
        try {
            Await.result(store.get(classOf[Host], hostId), awaitTimeout)
        } catch {
            case e: NotFoundException => newHost("default", hostId)
        }

        val p = Await.result(store.get(classOf[Port], port), awaitTimeout)
        store.update(p.toBuilder
                         .setHostId(hostId.asProto)
                         .setInterfaceName(portName).build())
        stateStore.setPortActive(port, hostId, active = true, tunnelKey = 0L)
                  .toBlocking.first()
    }

    override def newLoadBalancer(id: UUID = UUID.randomUUID): UUID = {
        store.create(createLoadBalancer(id, Some(true)))
        id
    }

    override def deleteLoadBalancer(id: UUID): Unit = {
        store.delete(classOf[LoadBalancer], id)
    }

    override def setLoadBalancerOnRouter(loadBalancer: UUID,
                                         router: UUID): Unit = {
        val r = Await.result(store.get(classOf[Router], router), awaitTimeout)
        val b = r.toBuilder
        if (loadBalancer != null) {
            b.setLoadBalancerId(loadBalancer.asProto)
        } else {
            b.clearLoadBalancerId()
        }
        store.update(b.build())
    }

    override def setLoadBalancerDown(loadBalancer: UUID): Unit = {
        val lb = Await.result(store.get(classOf[LoadBalancer], loadBalancer), awaitTimeout)
        store.update(lb.toBuilder.setAdminStateUp(false).build())
    }

    override def newVip(pool: UUID, address: String, port: Int): UUID = {
        val id = UUID.randomUUID
        store.create(createVip(id, poolId=Some(pool),
                               adminStateUp=Some(true),
                               address=Some(IPv4Addr.fromString(address)),
                               protocolPort=Some(port)))
        id
    }

    override def deleteVip(vip: UUID): Unit = {
        store.delete(classOf[Vip], vip)
    }

    override def matchVip(vip: UUID, address: IPAddr, protocolPort: Int): Boolean = {
        val other = store.observable(classOf[Vip], vip).toBlocking.first
        other.getAddress.equals(address.asProto) && other.getProtocolPort == protocolPort
    }

    override def newRandomVip(pool: UUID): UUID = {
        val rand = new Random()
        val id = UUID.randomUUID
        val address = IPv4Addr.fromString("10.10.10." + Integer.toString(rand.nextInt(200) +1))
        store.create(createVip(id,
                               address=Some(address),
                               protocolPort=Some(rand.nextInt(1000)+1),
                               poolId=Some(pool)))
        id
    }

    override def setVipAdminStateUp(vip: UUID, adminStateUp: Boolean): Unit = {
        val v = Await.result(store.get(classOf[Vip], vip), awaitTimeout)
        store.update(v.toBuilder
                         .setAdminStateUp(adminStateUp)
                         .build())
    }

    override def vipEnableStickySourceIP(vip: UUID): Unit = {
        val v = Await.result(store.get(classOf[Vip], vip), awaitTimeout)
        store.update(v.toBuilder
                         .setSessionPersistence(Vip.SessionPersistence.SOURCE_IP)
                         .build())
    }

    override def vipDisableStickySourceIP(vip: UUID): Unit = {
        val v = Await.result(store.get(classOf[Vip], vip), awaitTimeout)
        store.update(v.toBuilder
                         .clearSessionPersistence()
                         .build())
    }

    override def newHealthMonitor(id: UUID = UUID.randomUUID(),
                                  adminStateUp: Boolean = true,
                                  delay: Int = 2,
                                  maxRetries: Int = 2,
                                  timeout: Int = 2): UUID = {
        store.create(createHealthMonitor(id, adminStateUp,
                                         delay=Some(delay), timeout=Some(timeout),
                                         maxRetries=Some(maxRetries)))
        id
    }

    override def matchHealthMonitor(id: UUID, adminStateUp: Boolean,
                                    delay: Int, timeout: Int,
                                    maxRetries: Int): Boolean = {
        val hm = Await.result(store.get(classOf[HealthMonitor], id), awaitTimeout)
        hm.getAdminStateUp == adminStateUp && hm.getDelay == delay &&
            hm.getTimeout == timeout && hm.getMaxRetries == maxRetries
    }

    override def newRandomHealthMonitor
        (id: UUID = UUID.randomUUID()): UUID = {
        val rand = new Random
        store.create(createHealthMonitor(id, true,
                                         delay=Some(rand.nextInt(100) + 1),
                                         timeout=Some(rand.nextInt(100) + 1),
                                         maxRetries=Some(rand.nextInt(100) + 1)))
        id
    }

    override def setHealthMonitorDelay(id: UUID, delay: Int): Unit = {
        val hm = Await.result(store.get(classOf[HealthMonitor], id), awaitTimeout)
        store.update(hm.toBuilder
                         .setDelay(delay)
                         .build())
    }

    override def deleteHealthMonitor(hm: UUID): Unit = {
        store.delete(classOf[HealthMonitor], hm)
    }

    override def newPool(loadBalancer: UUID,
                         id: UUID = UUID.randomUUID,
                         adminStateUp: Boolean = true,
                         lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                         hmId: UUID = null): UUID = {
        store.create(createPool(id, healthMonitorId=Option(hmId),
                                loadBalancerId=Some(loadBalancer),
                                adminStateUp=Some(adminStateUp),
                                lbMethod=lbMethod))
        id
    }

    override def setPoolHealthMonitor(pool: UUID, hmId: UUID): Unit = {
        val p = Await.result(store.get(classOf[Pool], pool), awaitTimeout)
        store.update(p.toBuilder
                         .setHealthMonitorId(hmId.asProto)
                         .build())
    }

    override def setPoolAdminStateUp(pool: UUID, adminStateUp: Boolean): Unit = {
        val p = Await.result(store.get(classOf[Pool], pool), awaitTimeout)
        store.update(p.toBuilder
                         .setAdminStateUp(adminStateUp)
                         .build())
    }

    override def setPoolLbMethod(pool: UUID, lbMethod: PoolLBMethod): Unit = {
        val p = Await.result(store.get(classOf[Pool], pool), awaitTimeout)

        val builder = p.toBuilder
        if (lbMethod.isDefined) {
            builder.setLbMethod(lbMethod.get)
        } else {
            builder.clearLbMethod()
        }
        store.update(builder.build())
    }

    override def setPoolMapStatus(pool: UUID, status: PoolHealthMonitorMappingStatus): Unit = {
        val p = Await.result(store.get(classOf[Pool], pool), awaitTimeout)
        store.update(p.toBuilder
                         .setMappingStatus(status)
                         .build())
    }

    override def newPoolMember(pool: UUID, address: String, port: Int,
                               weight: Int = 1): UUID = {
        val id = UUID.randomUUID
        store.create(createPoolMember(id, adminStateUp=Some(true),
                                      poolId=Some(pool),
                                      address=Some(IPv4Addr.fromString(address)),
                                      protocolPort=Some(port),
                                      weight=Some(weight)))
        id
    }

    override def updatePoolMember(poolMember: UUID,
                                  poolId: Option[UUID] = None,
                                  adminStateUp: Option[Boolean] = None,
                                  weight: Option[Integer] = None,
                                  status: Option[LBStatus] = None): Unit = {
        val pm = Await.result(store.get(classOf[PoolMember], poolMember), awaitTimeout)
        val builder = pm.toBuilder
        poolId.foreach((id: UUID) => builder.setPoolId(id.asProto))
        adminStateUp.foreach(builder.setAdminStateUp(_))
        weight.foreach(builder.setWeight(_))
        status.foreach(builder.setStatus(_))
        store.update(builder.build())
    }

    override def deletePoolMember(poolMember: UUID): Unit = {
        store.delete(classOf[PoolMember], poolMember)
    }

    import VirtualConfigurationBuilders.TraceDeviceType
    override def newTraceRequest(device: UUID,
                                 devType: TraceDeviceType.TraceDeviceType,
                                 condition: rules.Condition,
                                 enabled: Boolean = false): UUID = {
        val id = UUID.randomUUID
        val builder = TraceRequest.newBuilder.setId(id.asProto)
        devType match {
            case TraceDeviceType.BRIDGE => builder.setNetworkId(device.asProto)
            case TraceDeviceType.ROUTER => builder.setRouterId(device.asProto)
            case TraceDeviceType.PORT => builder.setPortId(device.asProto)
        }
        builder.setCondition(setConditionFromCondition(Condition.newBuilder,
                                                       condition))
        store.create(builder.setEnabled(enabled).build())
        id
    }

    override def listTraceRequests(tenant: Option[String] = None): List[UUID] = {
        val requests = Await.result(store.getAll(classOf[TraceRequest]),
                                    awaitTimeout)

        def getTenant(tr: TraceRequest) = tr match {
            case tr if tr.hasPortId => {
                Await.result(store.get(classOf[Port], tr.getPortId),
                             awaitTimeout) match {
                    case p if p.hasRouterId =>
                        Await.result(store.get(classOf[Router], p.getRouterId),
                                     awaitTimeout).getTenantId
                    case p if p.hasNetworkId =>
                        Await.result(store.get(classOf[Network], p.getNetworkId),
                                     awaitTimeout).getTenantId
                }
            }
            case tr if tr.hasRouterId => {
                Await.result(store.get(classOf[Router], tr.getRouterId),
                             awaitTimeout).getTenantId
            }
            case tr if tr.hasNetworkId => {
                Await.result(store.get(classOf[Network], tr.getNetworkId),
                             awaitTimeout).getTenantId
            }
        }

        tenant match {
            case Some(t) => requests.filter(getTenant(_) == t).map(_.getId.asJava).toList
            case None => requests.map(_.getId.asJava).toList
        }
    }

    override def deleteTraceRequest(tr: UUID): Unit = {
        store.delete(classOf[TraceRequest], tr)
    }

    override def enableTraceRequest(tr: UUID): Unit = {
        val obj = Await.result(store.get(classOf[TraceRequest], tr), awaitTimeout)
        store.update(obj.toBuilder.setEnabled(true).build)
    }

    override def disableTraceRequest(tr: UUID): Unit = {
        val obj = Await.result(store.get(classOf[TraceRequest], tr), awaitTimeout)
        store.update(obj.toBuilder.setEnabled(false).build)
    }

    override def isTraceRequestEnabled(tr: UUID): Boolean = {
        Await.result(store.get(classOf[TraceRequest], tr), awaitTimeout).getEnabled
    }

    override def newQosPolicy(): UUID = {
        val id = UUID.randomUUID
        store.create(createQosPolicy(id=id))
        Await.result(store.get(classOf[QosPolicy], id), awaitTimeout)
        id
    }

    override def newQosBWLimitRule(policyId: UUID,
                          maxKbps: Int,
                          maxBurstKbps: Option[Int]): UUID = {
        val id = UUID.randomUUID
        store.create(createQosRuleBWLimit(policyId=policyId,
                                          maxKbps=maxKbps,
                                          maxBurstKbps=maxBurstKbps,
                                          id=id))
        Await.result(store.get(classOf[QosRuleBandwidthLimit], id), awaitTimeout)
        id
    }

    override def newQosDscpRule(policyId: UUID,
                                dscpMark: Int): UUID = {
        val id = UUID.randomUUID
        store.create(createQosRuleDscp(policyId=policyId,
                                       dscpMark=dscpMark,
                                       id=id))
        Await.result(store.get(classOf[QosRuleDscp], id), awaitTimeout)
        id
    }

    override def newServiceContainer(): UUID = {
        val id = UUID.randomUUID()
        val bldr = ServiceContainer.newBuilder.setId(id.asProto)
        store.create(bldr.build())
        id
    }

    def toSubnet(network: String, length: Int): IPSubnet[_] = {
        IPSubnet.fromString(s"${network}/${length}")
    }

    implicit def convertNextHop(from: NextHop): Route.NextHop = from.toProto()

    implicit def convertAction(from: RuleResult.Action): Action = from.toProto()

    implicit def convertNatTargets(from: Set[rules.NatTarget]): Set[NatTarget] = {
        from.map(f => createNatTarget(f.nwStart.asProto, f.nwEnd.asProto,
                                      f.tpStart, f.tpEnd))
    }

    implicit def convertLbMethod(from: PoolLBMethod): Option[Pool.PoolLBMethod] = {
        if (from == null) {
            None
        } else {
            Some(from.toProto)
        }
    }

    implicit def convertLbStatus(from: LBStatus): Commons.LBStatus = from.toProto()

    implicit def convertHmStatus(from: PoolHealthMonitorMappingStatus): Pool.PoolHealthMonitorMappingStatus =
        from.toProto()

    def setConditionFromCondition(builder: Condition.Builder,
                                  condition: rules.Condition): Condition.Builder = {
        setConditionParameters(builder,
                               Option(condition.conjunctionInv),
                               Option(condition.matchForwardFlow),
                               Option(condition.matchReturnFlow),
                               if (condition.inPortIds != null) {
                                   Some(condition.inPortIds.asScala.toSet)
                               } else { None },
                               Some(condition.inPortInv),
                               if (condition.outPortIds != null) {
                                   Some(condition.outPortIds.asScala.toSet)
                               } else { None },
                               Option(condition.outPortInv),
                               Option(condition.portGroup),
                               Option(condition.invPortGroup),
                               Option(condition.inPortGroup),
                               Option(condition.invInPortGroup),
                               Option(condition.outPortGroup),
                               Option(condition.invOutPortGroup),
                               Option(condition.ipAddrGroupIdSrc),
                               Option(condition.invIpAddrGroupIdSrc),
                               Option(condition.ipAddrGroupIdDst),
                               Option(condition.invIpAddrGroupIdDst),
                               someOrNone(condition.etherType),
                               Option(condition.invDlType),
                               Option(condition.ethSrc),
                               Option(condition.ethSrcMask),
                               Option(condition.invDlSrc),
                               Option(condition.ethDst),
                               Option(condition.dlDstMask),
                               Option(condition.invDlDst),
                               someOrNone(condition.nwTos),
                               Option(condition.nwTosInv),
                               someOrNone(condition.nwProto),
                               Option(condition.nwProtoInv),
                               Option(condition.nwSrcIp),
                               Option(condition.nwDstIp),
                               Option(condition.tpSrc),
                               Option(condition.tpDst),
                               Option(condition.nwSrcInv),
                               Option(condition.nwDstInv),
                               Option(condition.tpSrcInv),
                               Option(condition.tpDstInv),
                               Option(condition.traversedDevice),
                               Option(condition.traversedDeviceInv),
                               Option(condition.fragmentPolicy),
                               Option(condition.icmpDataSrcIp),
                               Option(condition.icmpDataSrcIpInv),
                               Option(condition.icmpDataDstIp),
                               Option(condition.icmpDataDstIpInv),
                               Option(condition.matchNwDstRewritten))
        builder
    }

    def someOrNone(ref: java.lang.Integer): Option[Int] = {
        if (ref != null) {
            Some(ref)
        } else {
            None
        }
    }

    def someOrNone(ref: java.lang.Byte): Option[Byte] = {
        if (ref != null) {
            Some(ref)
        } else {
            None
        }
    }

}
