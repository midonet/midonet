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
package org.midonet.cluster.topology

import org.midonet.cluster.data.ZoomConvert
import scala.collection.JavaConverters._

import com.google.protobuf.MessageOrBuilder

import org.scalatest.Matchers

import org.midonet.cluster.models.Topology.Vip.SessionPersistence
import org.midonet.cluster.models.Commons.{Condition => TopologyCondition}
import org.midonet.cluster.models.Topology.{Chain => TopologyChain,
                                            HealthMonitor => TopologyHealthMonitor,
                                            Host => TopologyHost,
                                            IPAddrGroup => TopologyIpAddrGroup,
                                            LoadBalancer => TopologyLB,
                                            Mirror => TopologyMirror,
                                            Network => TopologyBridge,
                                            Pool => TopologyPool,
                                            PoolMember => TopologyPoolMember,
                                            Port => TopologyPort,
                                            PortGroup => TopologyPortGroup,
                                            Route => TopologyRoute,
                                            Router => TopologyRouter,
                                            Rule => TopologyRule,
                                            Vip => TopologyVip}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules._
import org.midonet.midolman.simulation.{Bridge, Chain, IPAddrGroup, LoadBalancer, PortGroup, Router, Vip}
import org.midonet.midolman.state.l4lb
import org.midonet.cluster.topology.TopologyMatchers.{BridgeMatcher, BridgePortMatcher, ConditionMatcher, RouterPortMatcher, _}
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.simulation.{BridgePort, Port, _}
import org.midonet.packets.{IPv4Addr, MAC}

object TopologyMatchers {

    trait DeviceMatcher[M <: MessageOrBuilder] extends Matchers {
        def shouldBeDeviceOf(d: M): Unit
    }

    abstract class PortMatcher(val port: Port) extends DeviceMatcher[TopologyPort] {
        override def shouldBeDeviceOf(p: TopologyPort) = {
            port.id shouldBe p.getId.asJava
            if (p.hasInboundFilterId)
                port.inboundFilters should contain (p.getInboundFilterId.asJava)
            if (p.hasOutboundFilterId)
                port.outboundFilters should contain (p.getOutboundFilterId.asJava)
            port.tunnelKey shouldBe p.getTunnelKey
            port.portGroups.asScala.toSet shouldBe p.getPortGroupIdsList.asScala.map(_.asJava)
                .toSet
            port.peerId shouldBe (if (p.hasPeerId) p.getPeerId.asJava else null)
            port.hostId shouldBe (if (p.hasHostId) p.getHostId.asJava else null)
            port.interfaceName shouldBe (if (p.hasInterfaceName)
                p.getInterfaceName else null)
            port.adminStateUp shouldBe p.getAdminStateUp
            port.vlanId shouldBe p.getVlanId
        }
    }

    class BridgePortMatcher(port: BridgePort) extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.networkId shouldBe (if (p.hasNetworkId)
                p.getNetworkId.asJava else null)
        }
    }

    class RouterPortMatcher(port: RouterPort) extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.routerId shouldBe (if (p.hasRouterId)
                p.getRouterId.asJava else null)
            port.portSubnetV4 shouldBe (if (p.hasPortSubnet)
                p.getPortSubnet.asJava else null)
            port.portAddressV4 shouldBe (if (p.hasPortAddress)
                p.getPortAddress.asIPv4Address else null)
            port.portMac shouldBe (if (p.hasPortMac)
                MAC.fromString(p.getPortMac) else null)
        }
    }

    class VxLanPortMatcher(port: VxLanPort) extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.vtepId shouldBe (if (p.hasVtepId) p.getVtepId.asJava else null)
        }
    }

    class HostMatcher(host: Host) extends DeviceMatcher[TopologyHost] {
        override def shouldBeDeviceOf(h: TopologyHost): Unit = {
            host.id shouldBe h.getId.asJava
            host.portBindings.keys should contain theSameElementsAs h.getPortIdsList.asScala.map(_.asJava)
            host.tunnelZones.keys should contain theSameElementsAs h.getTunnelZoneIdsList.asScala.map(_.asJava)
        }
    }

    class MirrorMatcher(mirror: Mirror) extends TopologyMatchers with DeviceMatcher[TopologyMirror] {
        override def shouldBeDeviceOf(m: TopologyMirror): Unit = {
            mirror.id shouldBe m.getId.asJava
            mirror.toPort shouldBe m.getToPortId.asJava
            mirror.conditions.size() should === (m.getConditionsList.size())
            var i = 0
            while (i < mirror.conditions.size()) {
                mirror.conditions.get(i) shouldBeDeviceOf m.getConditionsList.get(i)
                i += 1
            }
        }
    }

    class BridgeMatcher(bridge: Bridge) extends DeviceMatcher[TopologyBridge] {
        override def shouldBeDeviceOf(b: TopologyBridge): Unit = {
            bridge.id shouldBe b.getId.asJava
            bridge.adminStateUp shouldBe b.getAdminStateUp
            bridge.tunnelKey shouldBe b.getTunnelKey
            if (b.hasInboundFilterId)
                bridge.inboundFilters should contain (b.getInboundFilterId.asJava)
            if (b.hasOutboundFilterId)
                bridge.outboundFilters should contain (b.getOutboundFilterId.asJava)
        }
    }

    class RouterMatcher(router: Router) extends Matchers
                                        with DeviceMatcher[TopologyRouter] {
        override def shouldBeDeviceOf(r: TopologyRouter): Unit = {
            router.id shouldBe r.getId.asJava
            router.cfg.adminStateUp shouldBe r.getAdminStateUp
            if (r.hasInboundFilterId)
                router.cfg.inboundFilters should contain (r.getInboundFilterId.asJava)
            if (r.hasOutboundFilterId)
                router.cfg.outboundFilters should contain (r.getOutboundFilterId.asJava)
            router.cfg.loadBalancer shouldBe (if (r.hasLoadBalancerId)
                r.getLoadBalancerId.asJava else null)
        }
    }

    class RouteMatcher(route: Route) extends Matchers
                                     with DeviceMatcher[TopologyRoute] {
        override def shouldBeDeviceOf(r: TopologyRoute): Unit = {
            route.srcNetworkAddr shouldBe (if (r.hasSrcSubnet)
                IPv4Addr(r.getSrcSubnet.getAddress).addr else 0)
            route.dstNetworkAddr shouldBe (if (r.hasDstSubnet)
                IPv4Addr(r.getDstSubnet.getAddress).addr else 0)
            route.nextHop shouldBe (r.getNextHop match {
                case TopologyRoute.NextHop.BLACKHOLE => NextHop.BLACKHOLE
                case TopologyRoute.NextHop.REJECT => NextHop.REJECT
                case TopologyRoute.NextHop.PORT => NextHop.PORT
                case TopologyRoute.NextHop.LOCAL => NextHop.LOCAL
            })
            route.nextHopPort shouldBe (if (r.hasNextHopPortId)
                r.getNextHopPortId.asJava else null)
            route.nextHopGateway shouldBe (if (r.hasNextHopGateway)
                IPv4Addr(r.getNextHopGateway.getAddress).toInt else 0)
            route.weight shouldBe r.getWeight
            route.attributes shouldBe (if (r.hasAttributes) r.getAttributes
            else null)
            route.routerId shouldBe (if (r.hasRouterId) r.getRouterId.asJava
            else null)
        }
    }

    class ChainMatcher(chain: Chain) extends Matchers
                                     with DeviceMatcher[TopologyChain] {

        override def shouldBeDeviceOf(c: TopologyChain): Unit = {
            chain.id shouldBe c.getId.asJava
            chain.name shouldBe c.getName
            chain.rules.asScala.map(_.id) should contain theSameElementsAs c
                .getRuleIdsList.asScala.map(_.asJava)
        }
    }

    class RuleMatcher(rule: Rule) extends Matchers
                                  with DeviceMatcher[TopologyRule]
                                  with TopologyMatchers {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            r.getId.asJava shouldBe rule.id
            if (r.hasAction) r.getAction.name shouldBe rule.action.name
            if (r.hasChainId) r.getChainId shouldBe rule.chainId.asProto

            if (r.hasCondition) rule.getCondition shouldBeDeviceOf r.getCondition
        }
    }

    class JumpRuleMatcher(rule: JumpRule) extends RuleMatcher(rule) {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            super.shouldBeDeviceOf(r)
            rule.jumpToChainID shouldBe r.getJumpRuleData.getJumpChainId.asJava
        }
    }

    class NatRuleMatcher(rule: NatRule) extends RuleMatcher(rule) {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            super.shouldBeDeviceOf(r)
            if (r.getCondition.getMatchForwardFlow) {
                val fwdNatRule = rule.asInstanceOf[DynamicForwardNatRule]
                fwdNatRule.getTargetsArray should not be null
                val targets = fwdNatRule.getNatTargets
                val protoTargets = r.getNatRuleData.getNatTargetsList.asScala
                targets should contain theSameElementsAs protoTargets.map(
                    target =>
                        new NatTarget(toIPv4Addr(target.getNwStart),
                                      toIPv4Addr(target.getNwEnd),
                                      target.getTpStart, target.getTpEnd)
                )
            }
        }
    }

    class Nat64RuleMatcher(rule: Nat64Rule) extends RuleMatcher(rule) {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            super.shouldBeDeviceOf(r)
            rule.portAddress shouldBe r.getNat64RuleData.getPortAddress.asJava
            rule.natPool shouldBe new NatTarget(
                r.getNat64RuleData.getNatPool.getNwStart.asIPv4Address,
                r.getNat64RuleData.getNatPool.getNwEnd.asIPv4Address,
                r.getNat64RuleData.getNatPool.getTpStart,
                r.getNat64RuleData.getNatPool.getTpEnd)
        }
    }

    class ConditionMatcher(cond: Condition) extends DeviceMatcher[TopologyCondition] {
        override def shouldBeDeviceOf(protoCond: TopologyCondition): Unit = {
            cond shouldBe ZoomConvert.fromProto(protoCond, classOf[Condition])
        }
    }

    class IpAddrGroupMatcher(ipAddrGroup: IPAddrGroup)
        extends Matchers with DeviceMatcher[TopologyIpAddrGroup] {

        override def shouldBeDeviceOf(g: TopologyIpAddrGroup): Unit = {
            ipAddrGroup.id shouldBe g.getId.asJava
            ipAddrGroup.addrs should contain theSameElementsAs
                g.getIpAddrPortsList.asScala.map(_.getIpAddress.asIPAddress)
        }
    }

    class PortGroupMatcher(portGroup: PortGroup)
        extends Matchers with DeviceMatcher[TopologyPortGroup] {
        override def shouldBeDeviceOf(pg: TopologyPortGroup): Unit = {
            portGroup.id shouldBe pg.getId.asJava
            portGroup.name shouldBe pg.getName
            portGroup.stateful shouldBe pg.getStateful
            portGroup.members should contain theSameElementsAs
                pg.getPortIdsList.asScala.map(_.asJava)
        }
    }

    class LoadBalancerMatcher(lb: LoadBalancer)
        extends Matchers with DeviceMatcher[TopologyLB] {
        override def shouldBeDeviceOf(l: TopologyLB): Unit = {
            lb.id shouldBe l.getId.asJava
            lb.adminStateUp shouldBe l.getAdminStateUp
            lb.routerId shouldBe (if (l.hasRouterId) l.getRouterId.asJava else null)
        }
    }

    class VipMatcher(vip: Vip) extends Matchers with DeviceMatcher[TopologyVip] {
        override def shouldBeDeviceOf(v: TopologyVip): Unit = {
            vip.id shouldBe v.getId.asJava
            vip.adminStateUp shouldBe v.getAdminStateUp
            vip.address shouldBe toIPv4Addr(v.getAddress)
            vip.protocolPort shouldBe v.getProtocolPort
            vip.isStickySourceIP shouldBe v.hasSessionPersistence &&
                                          v.getSessionPersistence == SessionPersistence.SOURCE_IP
            vip.poolId shouldBe v.getPoolId.asJava
        }
    }

    class PoolMatcher(pool: Pool) extends DeviceMatcher[TopologyPool] {
        override def shouldBeDeviceOf(p: TopologyPool): Unit = {
            pool.id shouldBe p.getId.asJava
            pool.adminStateUp shouldBe p.getAdminStateUp
            pool.members.map(_.id) should contain theSameElementsInOrderAs
                p.getPoolMemberIdsList.asScala.map(_.asJava)
        }
    }

    class PoolMemberMatcher(poolMember: PoolMember)
        extends DeviceMatcher[TopologyPoolMember] {
        override def shouldBeDeviceOf(pm: TopologyPoolMember): Unit = {
            poolMember.id shouldBe pm.getId.asJava
            poolMember.adminStateUp shouldBe pm.getAdminStateUp
            poolMember.status shouldBe (if (pm.hasStatus)
                l4lb.LBStatus.fromProto(pm.getStatus) else l4lb.LBStatus.ACTIVE)
            poolMember.address shouldBe (if (pm.hasAddress)
                pm.getAddress.asIPv4Address else null)
            poolMember.protocolPort shouldBe pm.getProtocolPort
            poolMember.weight shouldBe pm.getWeight
        }
    }

    class HealthMonitorMatcher(healthMonitor: HealthMonitor)
        extends DeviceMatcher[TopologyHealthMonitor] {
        override def shouldBeDeviceOf(hm: TopologyHealthMonitor): Unit = {
            healthMonitor.id shouldBe hm.getId.asJava
            healthMonitor.adminStateUp shouldBe hm.getAdminStateUp
            healthMonitor.healthMonitorType shouldBe
                l4lb.HealthMonitorType.fromProto(hm.getType)
            healthMonitor.status shouldBe l4lb.LBStatus.fromProto(hm.getStatus)
            healthMonitor.delay shouldBe hm.getDelay
            healthMonitor.timeout shouldBe hm.getTimeout
            healthMonitor.maxRetries shouldBe hm.getMaxRetries
        }
    }

}

trait TopologyMatchers {

    implicit def asHost(host: Host): DeviceMatcher[TopologyHost] = {
        new HostMatcher(host)
    }

    implicit def asMatcher(port: Port): DeviceMatcher[TopologyPort] = {
        port match {
            case p: BridgePort => asMatcher(p)
            case p: RouterPort => asMatcher(p)
            case p: VxLanPort => asMatcher(p)
        }
    }

    implicit def asMatcher(mirror: Mirror): MirrorMatcher =
        new MirrorMatcher(mirror)

    implicit def asMatcher(port: BridgePort): BridgePortMatcher =
        new BridgePortMatcher(port)

    implicit def asMatcher(port: RouterPort): RouterPortMatcher =
        new RouterPortMatcher(port)

    implicit def asMatcher(port: VxLanPort): VxLanPortMatcher =
        new VxLanPortMatcher(port)

    implicit def asMatcher(bridge: Bridge): BridgeMatcher =
        new BridgeMatcher(bridge)

    implicit def asMatcher(router: Router): RouterMatcher =
        new RouterMatcher(router)

    implicit def asMatcher(route: Route): RouteMatcher =
        new RouteMatcher(route)

    implicit def asMatcher(chain: Chain): ChainMatcher =
        new ChainMatcher(chain)

    implicit def asMatcher(rule: Rule): RuleMatcher =
        new RuleMatcher(rule)

    implicit def asMatcher(rule: JumpRule): RuleMatcher =
        new JumpRuleMatcher(rule)

    implicit def asMatcher(rule: NatRule): RuleMatcher =
        new NatRuleMatcher(rule)

    implicit def asMatcher(cond: Condition): ConditionMatcher =
        new ConditionMatcher(cond)

    implicit def asMatcher(ipAddrGroup: IPAddrGroup): IpAddrGroupMatcher =
        new IpAddrGroupMatcher(ipAddrGroup)

    implicit def asMatcher(portGroup: PortGroup): PortGroupMatcher =
        new PortGroupMatcher(portGroup)

    implicit def asMatcher(loadBalancer: LoadBalancer): LoadBalancerMatcher =
        new LoadBalancerMatcher(loadBalancer)

    implicit def asMatcher(vip: Vip): VipMatcher = new VipMatcher(vip)

    implicit def asMatcher(pool: Pool): PoolMatcher = new PoolMatcher(pool)

    implicit def asMatcher(poolMember: PoolMember): PoolMemberMatcher =
        new PoolMemberMatcher(poolMember)

    implicit def asMatcher(healthMonitor: HealthMonitor): HealthMonitorMatcher =
        new HealthMonitorMatcher(healthMonitor)

}
