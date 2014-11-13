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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.midolman.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}

@RunWith(classOf[JUnitRunner])
class PortConversionTest extends FeatureSpec with Matchers with TopologyBuilder
                         with TopologyMatchers {

    private val random = new Random()

    feature("Conversion for bridge port") {
        scenario("Test conversion from Protocol Buffers message") {
            val port = createBridgePort(
                bridgeId = Some(UUID.randomUUID),
                inboundFilterId = Some(UUID.randomUUID),
                outboundFilterId = Some(UUID.randomUUID),
                tunnelKey = random.nextLong(),
                peerId = Some(UUID.randomUUID),
                vifId = Some(UUID.randomUUID),
                hostId = Some(UUID.randomUUID),
                interfaceName = Some(random.nextString(10)),
                adminStateUp = random.nextBoolean(),
                portGroupIds = Set(UUID.randomUUID, UUID.randomUUID),
                vlanId = Some(random.nextInt().toShort))
            val device = ZoomConvert.fromProto(port, classOf[Port])

            device shouldBeDeviceOf port
            device.deviceTag should not be null
        }

        scenario("Test conversion to Protocol Buffers message") {
            val port = init(new BridgePort())
            port.networkId = UUID.randomUUID
            val proto = ZoomConvert.toProto(port, classOf[Topology.Port])

            port shouldBeDeviceOf proto
        }
    }

    feature("Conversion for router port") {
        scenario("Test conversion from Protocol Buffers message") {
            val port = createRouterPort(
                routerId = Some(UUID.randomUUID),
                inboundFilterId = Some(UUID.randomUUID),
                outboundFilterId = Some(UUID.randomUUID),
                tunnelKey = random.nextLong(),
                peerId = Some(UUID.randomUUID),
                vifId = Some(UUID.randomUUID),
                hostId = Some(UUID.randomUUID),
                interfaceName = Some(random.nextString(10)),
                adminStateUp = random.nextBoolean(),
                portGroupIds = Set(UUID.randomUUID, UUID.randomUUID))
            val device = ZoomConvert.fromProto(port, classOf[Port])

            device shouldBeDeviceOf port
            device.deviceTag should not be null
        }

        scenario("Test conversion to Protocol Buffers message") {
            val port = init(new RouterPort())
            port.routerId = UUID.randomUUID
            port.portSubnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))
            port.portIp = new IPv4Addr(random.nextInt())
            port.portMac = new MAC(random.nextLong())

            val proto = ZoomConvert.toProto(port, classOf[Topology.Port])

            port shouldBeDeviceOf proto
        }
    }

    feature("Conversion for VXLAN port") {
        scenario("Test conversion from Protocol Buffers message") {
            val port = createVxLanPort(
                bridgeId = Some(UUID.randomUUID),
                inboundFilterId = Some(UUID.randomUUID),
                outboundFilterId = Some(UUID.randomUUID),
                tunnelKey = random.nextLong(),
                peerId = Some(UUID.randomUUID),
                vifId = Some(UUID.randomUUID),
                hostId = Some(UUID.randomUUID),
                interfaceName = Some(random.nextString(10)),
                adminStateUp = random.nextBoolean(),
                portGroupIds = Set(UUID.randomUUID, UUID.randomUUID))
            val device = ZoomConvert.fromProto(port, classOf[Port])

            device shouldBeDeviceOf port
            device.deviceTag should not be null
        }

        scenario("Test conversion to Protocol Buffers message") {
            val port = init(new VxLanPort())
            port.networkId = UUID.randomUUID
            port.vtepId = UUID.randomUUID

            val proto = ZoomConvert.toProto(port, classOf[Topology.Port])

            port shouldBeDeviceOf proto
        }
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
}
