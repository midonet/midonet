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
package org.midonet.midolman.topology

import scala.collection.JavaConverters._

import com.google.protobuf.MessageOrBuilder

import org.scalatest.Matchers

import org.midonet.cluster.models.Topology.{IpAddrGroup => TopologyIPAddrGroup, Network => TopologyBridge, Port => TopologyPort, PortGroup => TopologyPortGroup, Route => TopologyRoute, Router => TopologyRouter, Rule => TopologyRule}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{Condition, ForwardNatRule, JumpRule, NatRule, NatTarget, Rule}
import org.midonet.midolman.simulation.{Bridge, IPAddrGroup, PortGroup, Router}
import org.midonet.midolman.topology.TopologyMatchers.{BridgeMatcher, BridgePortMatcher, RouterPortMatcher, _}
import org.midonet.midolman.topology.devices.{BridgePort, Port, RouterPort, VxLanPort}
import org.midonet.packets.{IPv4Addr, MAC}

object TopologyMatchers {

    trait DeviceMatcher[M <: MessageOrBuilder] {
        def shouldBeDeviceOf(d: M): Unit
    }

    abstract class PortMatcher(val port: Port) extends Matchers
                                               with DeviceMatcher[TopologyPort] {
        override def shouldBeDeviceOf(p: TopologyPort) = {
            port.id shouldBe p.getId.asJava
            port.inboundFilter shouldBe (if (p.hasInboundFilterId)
                p.getInboundFilterId.asJava else null)
            port.outboundFilter shouldBe (if (p.hasOutboundFilterId)
                p.getOutboundFilterId.asJava else null)
            port.tunnelKey shouldBe p.getTunnelKey
            port.portGroups shouldBe p.getPortGroupIdsList.asScala.map(_.asJava)
                .toSet
            port.peerId shouldBe (if (p.hasPeerId) p.getPeerId.asJava else null)
            port.hostId shouldBe (if (p.hasHostId) p.getHostId.asJava else null)
            port.interfaceName shouldBe (if (p.hasInterfaceName)
                p.getInterfaceName else null)
            port.adminStateUp shouldBe p.getAdminStateUp
            port.vlanId shouldBe p.getVlanId
        }
    }

    class BridgePortMatcher(port: BridgePort)
        extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.networkId shouldBe (if (p.hasNetworkId)
                p.getNetworkId.asJava else null)
        }
    }

    class RouterPortMatcher(port: RouterPort)
        extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.routerId shouldBe (if (p.hasRouterId)
                p.getRouterId.asJava else null)
            port.portSubnet shouldBe (if (p.hasPortSubnet)
                p.getPortSubnet.asJava else null)
            port.portIp shouldBe (if (p.hasPortAddress)
                p.getPortAddress.asIPv4Address else null)
            port.portMac shouldBe (if (p.hasPortMac)
                MAC.fromString(p.getPortMac) else null)
        }
    }

    class VxLanPortMatcher(port: VxLanPort)
        extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.vtepId shouldBe (if (p.hasVtepId) p.getVtepId.asJava else null)
        }
    }

    class BridgeMatcher(bridge: Bridge) extends Matchers
                                        with DeviceMatcher[TopologyBridge] {
        override def shouldBeDeviceOf(b: TopologyBridge): Unit = {
            bridge.id shouldBe b.getId.asJava
            bridge.adminStateUp shouldBe b.getAdminStateUp
            bridge.tunnelKey shouldBe b.getTunnelKey
            bridge.inFilterId shouldBe (if (b.hasInboundFilterId)
                Some(b.getInboundFilterId.asJava) else None)
            bridge.outFilterId shouldBe (if (b.hasOutboundFilterId)
                Some(b.getOutboundFilterId.asJava) else None)
            bridge.vxlanPortIds should contain theSameElementsAs
                b.getVxlanPortIdsList.asScala.map(_.asJava)
        }
    }

    class RouterMatcher(router: Router) extends Matchers
                                        with DeviceMatcher[TopologyRouter] {
        override def shouldBeDeviceOf(r: TopologyRouter): Unit = {
            router.id shouldBe r.getId.asJava
            router.cfg.adminStateUp shouldBe r.getAdminStateUp
            router.cfg.inboundFilter shouldBe (if (r.hasInboundFilterId)
                r.getInboundFilterId.asJava else null)
            router.cfg.outboundFilter shouldBe (if (r.hasOutboundFilterId)
                r.getOutboundFilterId.asJava else null)
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

    class RuleMatcher(rule: Rule) extends Matchers
                                  with DeviceMatcher[TopologyRule]
                                  with TopologyMatchers {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            r.getId.asJava shouldBe rule.id
            r.getAction.name shouldBe rule.action.name
            r.getChainId shouldBe rule.chainId.asProto

            rule.getCondition shouldBeDeviceOf r
        }
    }

    class JumpRuleMatcher(rule: JumpRule) extends RuleMatcher(rule) {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            super.shouldBeDeviceOf(r)
            rule.jumpToChainID shouldBe r.getJumpRuleData.getJumpTo.asJava
        }
    }

    class NatRuleMatcher(rule: NatRule) extends RuleMatcher(rule) {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            super.shouldBeDeviceOf(r)
            if (r.getMatchForwardFlow) {
                val fwdNatRule = rule.asInstanceOf[ForwardNatRule]
                fwdNatRule.getTargetsArray should not be null
                val targets = fwdNatRule.getNatTargets
                val protoTargets = r.getNatRuleData.getNatTargetsList.asScala
                targets should contain theSameElementsAs protoTargets.map(
                    target =>
                        new NatTarget(toIPv4Addr(target.getNwStart),
                                      toIPv4Addr(target.getNwEnd),
                                      target.getTpStart, target.getTpEnd)
                )

                if (targets.size() == 1) {
                    val target = targets.iterator.next

                    if (target.nwStart == target.nwEnd &&
                        target.tpStart == 0 && target.tpEnd == 0) {

                        fwdNatRule.isFloatingIp shouldBe true

                    } else
                        fwdNatRule.isFloatingIp shouldBe false
                } else
                    fwdNatRule.isFloatingIp shouldBe false
            }
        }
    }

    class ConditionMatcher(cond: Condition) extends Matchers
                                            with DeviceMatcher[TopologyRule] {
        override def shouldBeDeviceOf(r: TopologyRule): Unit = {
            r.getConjunctionInv shouldBe cond.conjunctionInv
            r.getMatchForwardFlow shouldBe cond.matchForwardFlow
            r.getMatchReturnFlow shouldBe cond.matchReturnFlow

            r.getInPortIdsCount shouldBe cond.inPortIds.size
            r.getInPortIdsList.asScala.map(_.asJava) should
                contain theSameElementsAs cond.inPortIds.asScala
            r.getInPortInv shouldBe cond.inPortInv

            r.getOutPortIdsCount shouldBe cond.outPortIds.size
            r.getOutPortIdsList.asScala.map(_.asJava) should
                contain theSameElementsAs cond.outPortIds.asScala
            r.getOutPortInv shouldBe cond.outPortInv

            r.getPortGroupId shouldBe cond.portGroup.asProto
            r.getInvPortGroup shouldBe cond.invPortGroup
            r.getIpAddrGroupIdSrc shouldBe cond.ipAddrGroupIdSrc.asProto
            r.getInvIpAddrGroupIdSrc shouldBe cond.invIpAddrGroupIdSrc
            r.getIpAddrGroupIdDst shouldBe cond.ipAddrGroupIdDst.asProto
            r.getInvIpAddrGroupIdDst shouldBe cond.invIpAddrGroupIdDst
            r.getDlType shouldBe cond.etherType
            r.getInvDlType shouldBe cond.invDlType
            r.getDlSrc shouldBe cond.ethSrc.toString
            r.getDlSrcMask shouldBe cond.ethSrcMask
            r.getInvDlSrc shouldBe cond.invDlSrc
            r.getDlDst shouldBe cond.ethDst.toString
            r.getDlDstMask shouldBe cond.dlDstMask
            r.getInvDlDst shouldBe cond.invDlDst
            r.getNwTos shouldBe cond.nwTos
            r.getNwTosInv shouldBe cond.nwTosInv
            r.getNwProto shouldBe cond.nwProto
            r.getNwProtoInv shouldBe cond.nwProtoInv
            r.getNwSrcIp shouldBe IPSubnetUtil.toProto(cond.nwSrcIp)
            r.getNwDstIp shouldBe IPSubnetUtil.toProto(cond.nwDstIp)
            r.getTpSrc shouldBe RangeUtil.toProto(cond.tpSrc)
            r.getTpDst shouldBe RangeUtil.toProto(cond.tpDst)
            r.getNwSrcInv shouldBe cond.nwSrcInv
            r.getNwDstInv shouldBe cond.nwDstInv
            r.getTpSrcInv shouldBe cond.tpSrcInv
            r.getTpDstInv shouldBe cond.tpDstInv
            r.getTraversedDevice shouldBe cond.traversedDevice.asProto
            r.getTraversedDeviceInv shouldBe cond.traversedDeviceInv
            r.getFragmentPolicy.name shouldBe cond.fragmentPolicy.name
        }
    }

    class IPAddrGroupMatcher(ipAddrGroup: IPAddrGroup)
        extends Matchers with DeviceMatcher[TopologyIPAddrGroup] {

        override def shouldBeDeviceOf(i: TopologyIPAddrGroup): Unit = {
            i.getId.asJava shouldBe ipAddrGroup.id
            ipAddrGroup.addrs should contain theSameElementsAs
            i.getIpAddrPortsList.asScala.map(ipAddrPort =>
                                                 toIPAddr(
                                                     ipAddrPort.getIpAddress)
            )
        }
    }

    class PortGroupMatcher(portGroup: PortGroup)
        extends Matchers with DeviceMatcher[TopologyPortGroup] {
        override def shouldBeDeviceOf(pg: TopologyPortGroup): Unit = {
            portGroup.id shouldBe pg.getId.asJava
            portGroup.name shouldBe (if (pg.hasName) pg.getName else null)
            portGroup.stateful shouldBe pg.getStateful
            portGroup.members should contain theSameElementsAs
                pg.getPortIdsList.asScala.map(_.asJava)
        }
    }
}

trait TopologyMatchers {

    implicit def asMatcher(port: Port): DeviceMatcher[TopologyPort] = {
        port match {
            case p: BridgePort => asMatcher(p)
            case p: RouterPort => asMatcher(p)
            case p: VxLanPort => asMatcher(p)
        }
    }

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

    implicit def asMatcher(rule: Rule): RuleMatcher =
        new RuleMatcher(rule)

    implicit def asMatcher(rule: JumpRule): RuleMatcher =
        new JumpRuleMatcher(rule)

    implicit def asMatcher(rule: NatRule): RuleMatcher =
        new NatRuleMatcher(rule)

    implicit def asMatcher(cond: Condition): ConditionMatcher =
        new ConditionMatcher(cond)

    implicit def asMatcher(ipAddrGroup: IPAddrGroup): IPAddrGroupMatcher =
        new IPAddrGroupMatcher(ipAddrGroup)

    implicit def asMatcher(portGroup: PortGroup): PortGroupMatcher =
        new PortGroupMatcher(portGroup)
}
