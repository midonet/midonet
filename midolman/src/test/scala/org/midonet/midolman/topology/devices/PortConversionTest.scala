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
package org.midonet.midolman.topology.devices

import java.util.UUID

import scala.util.Random
import scala.collection.JavaConversions._

import org.scalatest.{Matchers, FeatureSpec}

import org.midonet.cluster.client._
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.{Commons, Devices}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Subnet, MAC, IPv4Addr}

class PortConversionTest extends FeatureSpec with Matchers {

    private val random = new Random()

    feature("Conversion for bridge port") {
        scenario("Test conversion from Protocol Buffers message") {
            val proto = newProto
                .setNetworkId(UUID.randomUUID.asProto)
                .build()

            val pojo = ZoomConvert.fromProto(proto, classOf[Port])

            pojo should not be null
            pojo.getClass should be (classOf[BridgePort])

            val port = pojo.asInstanceOf[BridgePort]

            assertEquals(port, proto)
            port.deviceTag should not be null
        }

        scenario("Test conversion to Protocol Buffers message") {
            val port = init(new BridgePort())
            port.networkId = UUID.randomUUID

            val proto = ZoomConvert.toProto(port, classOf[Devices.Port])

            assertEquals(port, proto)
        }
    }

    feature("Conversion for router port") {
        scenario("Test conversion from Protocol Buffers message") {
            val proto = newProto
                .setRouterId(UUID.randomUUID.asProto)
                .setPortSubnet(newSubnet)
                .setPortAddress(newAddress)
                .setPortMac(new MAC(random.nextLong()).toString)
                .build()

            val pojo = ZoomConvert.fromProto(proto, classOf[Port])

            pojo should not be null
            pojo.getClass should be (classOf[RouterPort])

            val port = pojo.asInstanceOf[RouterPort]

            assertEquals(port, proto)
            port.deviceTag should not be null

            port.portAddr.getAddress.toString should be (proto.getPortAddress.getAddress)
            port.portAddr.getPrefixLen should be (proto.getPortSubnet.getPrefixLength)
        }

        scenario("Test conversion to Protocol Buffers message") {
            val port = init(new RouterPort())
            port.routerId = UUID.randomUUID
            port.portSubnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))
            port.portIp = new IPv4Addr(random.nextInt())
            port.portMac = new MAC(random.nextLong())

            val proto = ZoomConvert.toProto(port, classOf[Devices.Port])

            assertEquals(port, proto)
        }
    }

    feature("Conversion for VXLAN port") {
        scenario("Test conversion from Protocol Buffers message") {
            val proto = newProto
                .setVxlanMgmtIp(newAddress)
                .setVxlanMgmtPort(random.nextInt())
                .setVxlanTunnelIp(newAddress)
                .setVxlanTunnelZoneId(UUID.randomUUID.asProto)
                .setVxlanVni(random.nextInt())
                .build()

            val pojo = ZoomConvert.fromProto(proto, classOf[Port])

            pojo should not be null
            pojo.getClass should be (classOf[VxLanPort])

            val port = pojo.asInstanceOf[VxLanPort]

            assertEquals(port, proto)
            port.deviceTag should not be null
        }

        scenario("Test conversion to Protocol Buffers message") {
            val port = init(new VxLanPort())
            port.vxlanMgmtIp = new IPv4Addr(random.nextInt())
            port.vxlanMgmtPort = random.nextInt()
            port.vxlanTunnelIp = new IPv4Addr(random.nextInt())
            port.vxlanTunnelZoneId = UUID.randomUUID
            port.vxlanVni = random.nextInt()

            val proto = ZoomConvert.toProto(port, classOf[Devices.Port])

            assertEquals(port, proto)
        }
    }

    private def newProto = {
        Devices.Port.newBuilder
            .setId(UUID.randomUUID.asProto)
            .setInboundFilterId(UUID.randomUUID.asProto)
            .setOutboundFilterId(UUID.randomUUID.asProto)
            .setTunnelKey(random.nextLong())
            .addPortGroupIds(UUID.randomUUID.asProto)
            .addPortGroupIds(UUID.randomUUID.asProto)
            .setPeerId(UUID.randomUUID.asProto)
            .setHostId(UUID.randomUUID.asProto)
            .setInterfaceName(random.nextString(5))
            .setAdminStateUp(random.nextBoolean())
            .setVlanId(random.nextInt())
    }

    private def newAddress = {
        Commons.IPAddress.newBuilder
            .setVersion(Commons.IPVersion.V4)
            .setAddress(IPv4Addr(random.nextInt()).toString)
            .build()
    }

    private def newSubnet = {
        Commons.IPSubnet.newBuilder
            .setVersion(Commons.IPVersion.V4)
            .setAddress(IPv4Addr(random.nextInt()).toString)
            .setPrefixLength(random.nextInt(32))
            .build()
    }

    private def init(port: Port): port.type = {
        port.id = UUID.randomUUID
        port.inboundFilter = UUID.randomUUID
        port.outboundFilter = UUID.randomUUID
        port.tunnelKey = random.nextLong()
        port.portGroups = Set(UUID.randomUUID, UUID.randomUUID)
        port.peerId = UUID.randomUUID
        port.hostId = UUID.randomUUID
        port.interfaceName = random.nextString(5)
        port.adminStateUp = random.nextBoolean()
        port.vlanId = random.nextInt().toShort
        port
    }

    private def assertEquals(port: Port, proto: Devices.Port): Unit = {
        port.id should be (proto.getId.asJava)
        port.inboundFilter should be (proto.getInboundFilterId.asJava)
        port.outboundFilter should be (proto.getOutboundFilterId.asJava)
        port.tunnelKey should be (proto.getTunnelKey)
        port.portGroups.size should be (proto.getPortGroupIdsCount)
        proto.getPortGroupIdsList foreach(id => {
            port.portGroups should contain(id.asJava)
        })
        port.peerId should be (proto.getPeerId.asJava)
        port.hostId should be (proto.getHostId.asJava)
        port.interfaceName should be (proto.getInterfaceName)
        port.adminStateUp should be (proto.getAdminStateUp)
        port.vlanId should be (proto.getVlanId.toShort)
    }

    private def assertEquals(port: BridgePort, proto: Devices.Port): Unit = {
        assertEquals(port.asInstanceOf[Port], proto)
        port.networkId should be (proto.getNetworkId.asJava)
        port.deviceId should be (proto.getNetworkId.asJava)
    }

    private def assertEquals(port: RouterPort, proto: Devices.Port): Unit = {
        assertEquals(port.asInstanceOf[Port], proto)
        port.routerId should be (proto.getRouterId.asJava)
        port.portSubnet.getAddress.toString should be (proto.getPortSubnet.getAddress)
        port.portSubnet.getPrefixLen should be (proto.getPortSubnet.getPrefixLength)
        port.portIp.toString should be (proto.getPortAddress.getAddress)
        port.portMac.toString should be (proto.getPortMac)
        port.deviceId should be (proto.getRouterId.asJava)
    }

    private def assertEquals(port: VxLanPort, proto: Devices.Port): Unit = {
        assertEquals(port.asInstanceOf[Port], proto)
        port.vxlanMgmtIp.toString should be (proto.getVxlanMgmtIp.getAddress)
        port.vxlanMgmtPort should be (proto.getVxlanMgmtPort)
        port.vxlanTunnelIp.toString should be (proto.getVxlanTunnelIp.getAddress)
        port.vxlanTunnelZoneId should be (proto.getVxlanTunnelZoneId.asJava)
        port.vxlanVni should be (proto.getVxlanVni)
    }
}
