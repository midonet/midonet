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

import org.midonet.cluster.models.Topology.{Port => TopologyPort, Network => TopologyBridge}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.topology.TopologyMatchers.{BridgeMatcher, VxLanPortMatcher, RouterPortMatcher, BridgePortMatcher}
import org.midonet.midolman.topology.devices.{VxLanPort, RouterPort, Port, BridgePort}
import org.midonet.packets.MAC

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

    class VxLanPortMatcher(port: VxLanPort)
        extends PortMatcher(port) {
        override def shouldBeDeviceOf(p: TopologyPort): Unit = {
            super.shouldBeDeviceOf(p)
            port.vtepMgmtIp shouldBe p.getVtepMgmtIp.asIPv4Address
            port.vtepMgmtPort shouldBe p.getVtepMgmtPort
            port.vtepTunnelIp shouldBe p.getVtepTunnelIp.asIPv4Address
            port.vtepTunnelZoneId shouldBe p.getVtepTunnelZoneId.asJava
            port.vtepVni shouldBe p.getVtepVni
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
}

trait TopologyMatchers {

    implicit def asMatcher(port: BridgePort): BridgePortMatcher =
        new BridgePortMatcher(port)

    implicit def asMatcher(port: RouterPort): RouterPortMatcher =
        new RouterPortMatcher(port)

    implicit def asMatcher(port: VxLanPort): VxLanPortMatcher =
        new VxLanPortMatcher(port)

    implicit def asMatcher(bridge: Bridge): BridgeMatcher =
        new BridgeMatcher(bridge)

}
