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

import org.midonet.cluster.models.Topology.{Network => TopologyBridge, Port => TopologyPort, Route => TopologyRoute, Router => TopologyRouter}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.simulation.{Router, Bridge}
import org.midonet.midolman.topology.TopologyMatchers._
import org.midonet.midolman.topology.devices.{BridgePort, Port, RouterPort}
import org.midonet.packets.{IPv4Addr, MAC}

object TopologyMatchers {

    trait DeviceMatcher[M <: MessageOrBuilder] {
        def shouldBeDeviceOf(d: M): Unit
    }

    abstract class PortMatcher(val port: Port) extends Matchers
                                               with DeviceMatcher[TopologyPort] {
        override def shouldBeDeviceOf(p: TopologyPort) = {
            port.id shouldBe p.getId.asJava
            port.inboundFilter shouldBe p.getInboundFilterId.asJava
            port.outboundFilter shouldBe p.getOutboundFilterId.asJava
            port.tunnelKey shouldBe p.getTunnelKey
            port.portGroups shouldBe p.getPortGroupIdsList.asScala.map(_.asJava)
                .toSet
            port.peerId shouldBe p.getPeerId.asJava
            port.hostId shouldBe p.getHostId.asJava
            port.interfaceName shouldBe p.getInterfaceName
            port.adminStateUp shouldBe p.getAdminStateUp
            port.vlanId shouldBe p.getVlanId
        }
    }

    class BridgePortMatcher(port: BridgePort)
        extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.networkId shouldBe p.getNetworkId.asJava
        }
    }

    class RouterPortMatcher(port: RouterPort)
        extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.routerId shouldBe p.getRouterId.asJava
            port.portSubnet shouldBe p.getPortSubnet.asJava
            port.portIp shouldBe p.getPortAddress.asIPv4Address
            port.portMac shouldBe MAC.fromString(p.getPortMac)
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

}

trait TopologyMatchers {

    implicit def asMatcher(port: BridgePort): BridgePortMatcher =
        new BridgePortMatcher(port)

    implicit def asMatcher(port: RouterPort): RouterPortMatcher =
        new RouterPortMatcher(port)

    implicit def asMatcher(bridge: Bridge): BridgeMatcher =
        new BridgeMatcher(bridge)

    implicit def asMatcher(router: Router): RouterMatcher =
        new RouterMatcher(router)

    implicit def asMatcher(route: Route): RouteMatcher =
        new RouteMatcher(route)
}
