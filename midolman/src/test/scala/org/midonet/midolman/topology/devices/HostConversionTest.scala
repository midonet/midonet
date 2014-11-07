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

import scala.collection.JavaConversions._

import org.scalatest.{FeatureSpec, Matchers}
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.IPAddr

class HostConversionTest extends FeatureSpec with Matchers {

    feature("Conversion for host") {
        scenario("Test conversion from Protocol Buffer message") {
            val proto = newProto
            val zoomObj = ZoomConvert.fromProto(proto, classOf[Host])

            zoomObj should not be None
            assertEquals(proto, zoomObj)
        }

        scenario("Test conversion to Protocol Buffer message") {
            val zoomObj = newZoomObj
            val proto = ZoomConvert.toProto(zoomObj, classOf[Topology.Host])

            proto should not be None
            assertEquals(proto, zoomObj)
        }
    }

    private def assertEquals(proto: Topology.Host, zoomObj: Host) = {
        proto.getId.asJava should be(zoomObj.id)
        proto.getName should be(zoomObj.name)
        proto.getAlive should be(zoomObj.alive)
        proto.getAddressesCount should be(zoomObj.addresses.size)
        for (addr <- proto.getAddressesList) {
            zoomObj.addresses should contain(IPAddressUtil.toIPAddr(addr))
        }
        proto.getPortInterfaceMappingCount should be (zoomObj.portInterfaceMapping.size)
        for (portMapping <- proto.getPortInterfaceMappingList) {
            zoomObj.portInterfaceMapping should contain(portMapping)
        }
        proto.getTunnelZoneIdsCount should be(zoomObj.tunnelZoneIds.size)
        for (tunnelId <- proto.getTunnelZoneIdsList) {
            zoomObj.tunnelZoneIds should contain(tunnelId.asJava)
        }
        proto.getFloodingProxyWeight should be(zoomObj.floodingProxyWeight)

        // The tunnelZones field of the zoomObj should be None since no such field
        // exists in the proto.
        Option(zoomObj.tunnelZones) should be(None)
    }

    private def interfacePortMapping(interface: String): Topology.HostInterfacePort = {
        Topology.HostInterfacePort.newBuilder
        .setId(UUID.randomUUID().asProto)
        .setPortId(UUID.randomUUID().asProto)
        .setInterfaceName(interface)
        .build()
    }

    private def newZoomObj = {
        val zoomObj = new Host
        zoomObj.id = UUID.randomUUID()
        zoomObj.name = "tata"
        zoomObj.alive = true
        zoomObj.addresses = Set(IPAddr.fromString("192.168.0.1"),
                                IPAddr.fromString("192.168.0.2"))
        zoomObj.portInterfaceMapping = Set(interfacePortMapping("foo"),
                                           interfacePortMapping("bar"))
        zoomObj.tunnelZoneIds = Set(UUID.randomUUID(),
                                    UUID.randomUUID())
        zoomObj.floodingProxyWeight = 42
        zoomObj
    }

    private def newProto = {
        Topology.Host.newBuilder
        .setId(UUID.randomUUID().asProto)
        .setName("tata")
        .setAlive(true)
        .addAddresses("192.168.0.1".asProtoIPAddress)
        .addAddresses("192.168.0.2".asProtoIPAddress)
        .addPortInterfaceMapping(interfacePortMapping("foo"))
        .addPortInterfaceMapping(interfacePortMapping("bar"))
        .addTunnelZoneIds(UUID.randomUUID().asProto)
        .addTunnelZoneIds(UUID.randomUUID().asProto)
        .setFloodingProxyWeight(42)
        .build()
    }
}
