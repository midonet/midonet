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

import java.util.{ArrayList, Random, UUID}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import com.google.inject.Inject

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.models.Topology.Rule.{Action, NatTarget}
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.LegacyStorage
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.{Chain => SimChain}
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus
import org.midonet.midolman.state.l4lb.{LBStatus, PoolLBMethod}
import org.midonet.midolman.topology.{TopologyBuilder, VirtualTopology}
import org.midonet.packets.{IPAddr, IPv4Addr, IPv4Subnet, IPSubnet, MAC}
import org.midonet.util.functors._
import org.midonet.util.reactivex.{AwaitableObserver, TestAwaitableObserver}

class ZoomVirtualConfigurationBuilders @Inject()(backend: MidonetBackend,
                                                 stateStorage: LegacyStorage)
        extends VirtualConfigurationBuilders
        with TopologyBuilder {

    val store = backend.store

    override def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID = {
        store.create(createHost(id, tunnelZoneIds=tunnelZones))
        id
    }

    override def isHostAlive(id: UUID): Boolean = ???
    override def addHostVrnPortMapping(host: UUID, port: UUID, iface: String): Unit = ???

    override def newInboundChainOnBridge(name: String, bridge: UUID): UUID = {
        val chain = newChain(name)
        store.observable(classOf[Network], bridge).take(1)
            .map[Network](makeFunc1[Network,Network]{
                              Network.newBuilder().mergeFrom(_)
                                  .setInboundFilterId(chain.asProto)
                                  .build()
                          })
            .subscribe(makeAction1[Network]({ store.update(_) }))
        chain
    }
    override def newOutboundChainOnBridge(name: String, bridge: UUID): UUID = {
        val chain = newChain(name)
        store.observable(classOf[Network], bridge).take(1)
            .map[Network](makeFunc1[Network,Network]{
                              Network.newBuilder().mergeFrom(_)
                                  .setOutboundFilterId(chain.asProto)
                                  .build()
                          })
            .subscribe(makeAction1[Network]({ store.update(_) }))
        chain
    }
    override def newInboundChainOnRouter(name: String, router: UUID): UUID = {
        val chain = newChain(name)
        store.observable(classOf[Router], router).take(1)
            .map[Router](makeFunc1[Router,Router]{
                             Router.newBuilder().mergeFrom(_)
                                 .setInboundFilterId(chain.asProto)
                                 .build()
                         })
            .subscribe(makeAction1[Router]({ store.update(_) }))

        chain
    }
    override def newOutboundChainOnRouter(name: String, router: UUID): UUID =  {
        val chain = newChain(name)
        store.observable(classOf[Router], router).take(1)
            .map[Router](makeFunc1[Router,Router]{
                             Router.newBuilder().mergeFrom(_)
                                 .setOutboundFilterId(chain.asProto)
                                 .build()
                         })
            .subscribe(makeAction1[Router]({ store.update(_) }))
        chain
    }

    override def newChain(name: String, id: Option[UUID] = None): UUID = {
        val chain = id.getOrElse(UUID.randomUUID)
        store.create(createChain(chain, Some(name)))
        chain
    }

    override def newOutboundChainOnPort(name: String, port: UUID, id: UUID): UUID = {
        val chain = newChain(name)
        store.observable(classOf[Port], port).take(1)
            .map[Port](makeFunc1[Port,Port]{
                           Port.newBuilder().mergeFrom(_)
                               .setOutboundFilterId(chain.asProto)
                               .build()
                       })
            .subscribe(makeAction1[Port]({ store.update(_) }))
        chain
    }

    override def newInboundChainOnPort(name: String, port: UUID, id: UUID): UUID = {
        val chain = newChain(name)
        store.observable(classOf[Port], port).take(1)
            .map[Port](makeFunc1[Port,Port]{
                           Port.newBuilder().mergeFrom(_)
                               .setInboundFilterId(chain.asProto)
                               .build()
                       })
            .subscribe(makeAction1[Port]({ store.update(_) }))
        chain
    }

    def insertRuleFunc(pos: Int, rule: UUID) =
        makeFunc1[Chain,Chain](c => {
                                   val rules = new ArrayList[Commons.UUID]
                                   rules.addAll(c.getRuleIdsList())
                                   rules.add(pos-1, rule.asProto)
                                   Chain.newBuilder().mergeFrom(c)
                                       .clearRuleIds()
                                       .addAllRuleIds(rules)
                                       .build()
                               })

    override def newLiteralRuleOnChain(chain: UUID, pos: Int, condition: rules.Condition,
                                       action: RuleResult.Action): UUID = {
        val rule = UUID.randomUUID
        val builder = createLiteralRuleBuilder(rule, None, Some(action))
        store.create(setConditionFromCondition(builder, condition).build())

        store.observable(classOf[Chain], chain).take(1)
            .map[Chain](insertRuleFunc(pos, rule))
            .subscribe(makeAction1[Chain]({ store.update(_) }))
        rule
    }

    override def newTraceRuleOnChain(chain: UUID, pos: Int,
                                     condition: rules.Condition,
                                     requestId: UUID): UUID = ???

    override def newForwardNatRuleOnChain(chain: UUID, pos: Int,
                                          condition: rules.Condition,
                                          action: RuleResult.Action, targets: Set[rules.NatTarget],
                                          isDnat: Boolean) : UUID = {
        val id = UUID.randomUUID
        val builder = createNatRuleBuilder(id, None, Option(isDnat),
                                           None, targets)
        store.create(setConditionFromCondition(builder, condition).build())
        store.observable(classOf[Chain], chain).take(1)
            .map[Chain](insertRuleFunc(pos, id))
            .subscribe(makeAction1[Chain]({ store.update(_) }))
        id
    }

    override def newReverseNatRuleOnChain(chain: UUID, pos: Int,
                                          condition: rules.Condition,
                                          action: RuleResult.Action, isDnat: Boolean) : UUID = {
        val id = UUID.randomUUID
        val builder = createNatRuleBuilder(id, None, Option(isDnat),
                                           None, reverse=true)
        store.create(setConditionFromCondition(builder, condition).build())
        store.observable(classOf[Chain], chain).take(1)
            .map[Chain](insertRuleFunc(pos, id))
            .subscribe(makeAction1[Chain]({ store.update(_) }))
        id
    }

    override def removeRuleFromBridge(bridge: UUID): Unit = {
        store.observable(classOf[Network], bridge).take(1)
            .map[Network](makeFunc1[Network,Network]{
                              Network.newBuilder().mergeFrom(_)
                                  .clearInboundFilterId()
                                  .build()
                          })
            .subscribe(makeAction1[Network]({ store.update(_) }))
    }

    override def newJumpRuleOnChain(chain: UUID, pos: Int,
                                    condition: rules.Condition,
                                    jumpToChainID: UUID): UUID = {
        val id = UUID.randomUUID
        val builder = createJumpRuleBuilder(id, None, Some(jumpToChainID))
        store.create(setConditionFromCondition(builder, condition).build())
        store.observable(classOf[Chain], chain).take(1)
            .map[Chain](insertRuleFunc(pos, id))
            .subscribe(makeAction1[Chain]({ store.update(_) }))

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
        store.observable(classOf[IPAddrGroup], id).take(1)
            .map[IPAddrGroup](makeFunc1[IPAddrGroup, IPAddrGroup](
                                  g => {
                                      IPAddrGroup.newBuilder().mergeFrom(g)
                                          .addIpAddrPorts(IPAddrGroup.IPAddrPorts.newBuilder()
                                                              .setIpAddress(IPv4Addr(addr).asProto)
                                                              .build())
                                          .build()
                                  }))
            .subscribe(makeAction1[IPAddrGroup]({ store.update(_) }))
    }

    override def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit = {
        store.observable(classOf[IPAddrGroup], id).take(1)
            .map[IPAddrGroup](makeFunc1[IPAddrGroup, IPAddrGroup](
                                  g => {
                                      val ports = g.getIpAddrPortsList.asScala
                                          .filter(_.getIpAddress != IPv4Addr(addr).asProto)
                                          .asJava
                                      IPAddrGroup.newBuilder().mergeFrom(g)
                                          .clearIpAddrPorts()
                                          .addAllIpAddrPorts(ports)
                                          .build()
                                  }))
            .subscribe(makeAction1[IPAddrGroup]({ store.update(_) }))

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
        store.observable(classOf[TunnelZone], tz).take(1)
            .map[TunnelZone](makeFunc1[TunnelZone,TunnelZone](
                                 (tz: TunnelZone) => {
                                     val b = TunnelZone.newBuilder().mergeFrom(tz)
                                     val hosts = new ArrayList[HostToIp]
                                     hosts.addAll(tz.getHostsList())
                                     hosts.add(HostToIp.newBuilder()
                                                   .setHostId(host.asProto)
                                                   .setIp(ip.asProto).build())
                                     b.build()
                                 }
                             ))
            .subscribe(makeAction1[TunnelZone]({ store.update(_) }))
    }

    override def deleteTunnelZoneMember(tz: UUID, host: UUID): Unit = ???

    override def newBridge(name: String, tenant: Option[String] = None): UUID = {
        val id = UUID.randomUUID
        store.create(createBridge(id, tenant, Some(name), adminStateUp=true))
        id
    }
    override def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit = ???
    override def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit = ???
    override def deleteBridge(bridge: UUID): Unit = ???

    override def newBridgePort(bridge: UUID,
                               host: Option[UUID] = None,
                               interface: Option[String] = None): UUID = {
        val id = UUID.randomUUID
        store.create(createBridgePort(id, bridgeId=Some(bridge),
                                      hostId=host, interfaceName=interface,
                                      adminStateUp=true))
        id
    }

    override def setPortAdminStateUp(port: UUID, state: Boolean): Unit = ???

    override def deletePort(port: UUID): Unit = {
        val devObserver = new TestAwaitableObserver[VirtualTopology.Device]
        VirtualTopology.observable[VirtualTopology.Device](port).subscribe(devObserver)
        store.delete(classOf[Port], port)
        devObserver.awaitCompletion(5 seconds)
    }
    override def deletePort(port: UUID, hostId: UUID): Unit = ???
    override def newPortGroup(name: String, stateful: Boolean = false): UUID = ???
    override def setPortGroupStateful(id: UUID, stateful: Boolean): Unit = ???
    override def newPortGroupMember(pgId: UUID, portId: UUID): Unit = ???
    override def deletePortGroupMember(pgId: UUID, portId: UUID): Unit = ???

    override def newRouter(name: String): UUID = {
        val id  = UUID.randomUUID
        store.create(createRouter(id, name=Some(name), adminStateUp=true))
        id
    }
    override def setRouterAdminStateUp(router: UUID, state: Boolean): Unit = ???
    override def deleteRouter(router: UUID): Unit = ???

    override def newRouterPort(router: UUID, mac: MAC, portAddr: String,
                               nwAddr: String, nwLen: Int): UUID = {
        val id = UUID.randomUUID
        val addr = IPv4Addr.fromString(portAddr)
        store.create(createRouterPort(id, routerId=Some(router),
                                      portMac=mac,
                                      portAddress=addr,
                                      portSubnet=toSubnet(nwAddr, nwLen),
                                      adminStateUp=true))

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
                        .setTunnelZoneId(tunnelZone.asProto)
                        .addTunnelIps(tunnelIp.toString()).build())
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

    override def addDhcpSubnet(bridge: UUID,
                               subnet: IPv4Subnet,
                               gw: IPv4Addr,
                               dns: List[IPv4Addr],
                               opt121routes: List[VirtualConfigurationBuilders.DhcpOpt121Route]): IPv4Subnet = ???
    override def addDhcpHost(bridge: UUID, subnet: IPv4Subnet,
                             hostMac: MAC, hostIp: IPv4Addr): MAC = ???
    override def setDhcpHostOptions(bridge: UUID,
                                    subnet: IPv4Subnet, host: MAC,
                                    options: Map[String, String]): Unit = ???

    override def linkPorts(port: UUID, peerPort: UUID): Unit = ???
    override def unlinkPorts(port: UUID): Unit = ???

    override def materializePort(port: UUID, hostId: UUID, portName: String): Unit = {
        try {
            store.observable(classOf[Host], hostId).toBlocking.first
        } catch {
            case e: NotFoundException => newHost("default", hostId)
        }

        store.update(store.observable(classOf[Port], port)
                         .map[Port](makeFunc1[Port, Port](
                                        Port.newBuilder.mergeFrom(_)
                                            .setHostId(hostId.asProto)
                                            .setInterfaceName(portName).build()))
                         .toBlocking.first())
        stateStorage.setPortLocalAndActive(port, hostId, true)
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
        store.observable(classOf[Router], router).take(1)
            .map[Router](makeFunc1[Router,Router](
                             Router.newBuilder().mergeFrom(_)
                                 .setLoadBalancerId(loadBalancer.asProto)
                                 .build()))
            .subscribe(makeAction1[Router]({ store.update(_) }))
    }

    override def setLoadBalancerDown(loadBalancer: UUID): Unit = {
        store.observable(classOf[LoadBalancer], loadBalancer).take(1)
            .map[LoadBalancer](makeFunc1[LoadBalancer,LoadBalancer](
                                   LoadBalancer.newBuilder().mergeFrom(_)
                                       .setAdminStateUp(false)
                                       .build()))
            .subscribe(makeAction1[LoadBalancer]({ store.update(_) }))
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
        store.observable(classOf[Vip], vip).take(1)
            .map[Vip](makeFunc1[Vip,Vip](Vip.newBuilder().mergeFrom(_)
                                             .setAdminStateUp(adminStateUp)
                                             .build()))
            .subscribe(makeAction1[Vip]({ store.update(_) }))
    }

    override def vipEnableStickySourceIP(vip: UUID): Unit = {
        store.observable(classOf[Vip], vip).take(1)
            .map[Vip](makeFunc1[Vip,Vip](Vip.newBuilder().mergeFrom(_)
                                             .setSessionPersistence(Vip.SessionPersistence.SOURCE_IP)
                                             .build()))
            .subscribe(makeAction1[Vip]({ store.update(_) }))
    }

    override def vipDisableStickySourceIP(vip: UUID): Unit = {
        store.observable(classOf[Vip], vip).take(1)
            .map[Vip](makeFunc1[Vip,Vip](Vip.newBuilder().mergeFrom(_)
                                             .clearSessionPersistence()
                                             .build()))
            .subscribe(makeAction1[Vip]({ store.update(_) }))
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
        val hm = store.observable(classOf[HealthMonitor], id).toBlocking.first
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

    override def setHealthMonitorDelay(hm: UUID, delay: Int): Unit = {
        store.observable(classOf[HealthMonitor], hm).take(1)
            .map[HealthMonitor](makeFunc1[HealthMonitor,HealthMonitor](
                                    HealthMonitor.newBuilder().mergeFrom(_)
                                        .setDelay(delay)
                                        .build()))
            .subscribe(makeAction1[HealthMonitor]({ store.update(_) }))
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
        store.observable(classOf[Pool], pool).take(1)
            .map[Pool](makeFunc1[Pool,Pool](
                           Pool.newBuilder().mergeFrom(_)
                               .setHealthMonitorId(hmId.asProto)
                               .build()))
            .subscribe(makeAction1[Pool]({ store.update(_) }))
    }

    override def setPoolAdminStateUp(pool: UUID, adminStateUp: Boolean): Unit = {
        store.observable(classOf[Pool], pool).take(1)
            .map[Pool](makeFunc1[Pool,Pool](
                           Pool.newBuilder().mergeFrom(_)
                               .setAdminStateUp(adminStateUp)
                               .build()))
            .subscribe(makeAction1[Pool]({ store.update(_) }))
    }

    override def setPoolLbMethod(pool: UUID, lbMethod: PoolLBMethod): Unit = {
        store.observable(classOf[Pool], pool).take(1)
            .map[Pool](makeFunc1[Pool,Pool](
                           (p: Pool) => {
                               val builder = Pool.newBuilder().mergeFrom(p)
                               if (lbMethod.isDefined) {
                                   builder.setLbMethod(lbMethod.get)
                               } else {
                                   builder.clearLbMethod()
                               }
                               builder.build()
                           }))
            .subscribe(makeAction1[Pool]({ store.update(_) }))
    }

    override def setPoolMapStatus(pool: UUID, status: PoolHealthMonitorMappingStatus): Unit = {
        store.observable(classOf[Pool], pool).take(1)
            .map[Pool](makeFunc1[Pool,Pool](
                           Pool.newBuilder().mergeFrom(_)
                               .setMappingStatus(status)
                               .build()))
            .subscribe(makeAction1[Pool]({ store.update(_) }))
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
        def updateMember(old: PoolMember): PoolMember = {
            val builder = PoolMember.newBuilder().mergeFrom(old)
            poolId.foreach((id: UUID) => builder.setPoolId(id.asProto))
            adminStateUp.foreach(builder.setAdminStateUp(_))
            weight.foreach(builder.setWeight(_))
            status.foreach(builder.setStatus(_))
            builder.build()
        }
        store.observable(classOf[PoolMember], poolMember).take(1)
            .map[PoolMember](makeFunc1[PoolMember,PoolMember](updateMember))
            .subscribe(makeAction1[PoolMember]({ store.update(_) }))
    }

    override def deletePoolMember(poolMember: UUID): Unit = {
        store.delete(classOf[PoolMember], poolMember)
    }

    import VirtualConfigurationBuilders.TraceDeviceType
    override def newTraceRequest(device: UUID,
                                 devType: TraceDeviceType.TraceDeviceType,
                                 condition: rules.Condition,
                                 enabled: Boolean = false): UUID = ???
    override def listTraceRequests(tenant: Option[String] = None): List[UUID] = ???
    override def deleteTraceRequest(tr: UUID): Unit = ???
    override def enableTraceRequest(tr: UUID): Unit = ???
    override def disableTraceRequest(tr: UUID): Unit = ???
    override def isTraceRequestEnabled(tr: UUID): Boolean = ???

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

    def setConditionFromCondition(rule: Rule.Builder,
                                  condition: rules.Condition): Rule.Builder = {
        setCondition(rule,
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
                     Option(condition.fragmentPolicy))
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
