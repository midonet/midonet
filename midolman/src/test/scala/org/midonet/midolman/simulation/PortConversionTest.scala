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
package org.midonet.midolman.simulation

import java.util.UUID

import akka.actor.ActorSystem

import org.junit.runner.RunWith

import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}
import com.google.common.collect.Lists

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import scala.util.Random

import org.midonet.cluster.state.PortStateStorage.PortInactive

@RunWith(classOf[JUnitRunner])
class PortConversionTest extends FeatureSpec with Matchers with TopologyBuilder
                         with TopologyMatchers {

    private val random = new Random()
    implicit val as: ActorSystem = ActorSystem("port-conversion-test")

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
            val device = Port(port,
                              PortInactive,
                              Lists.newArrayList(port.getInboundFilterId.asJava),
                              Lists.newArrayList(port.getOutboundFilterId.asJava))

            device shouldBeDeviceOf port
            device.deviceTag should not be null
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
            val device = Port(port,
                              PortInactive,
                              Lists.newArrayList(port.getInboundFilterId.asJava),
                              Lists.newArrayList(port.getOutboundFilterId.asJava))

            device shouldBeDeviceOf port
            device.deviceTag should not be null
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
                hostId = None,
                interfaceName = None,
                adminStateUp = random.nextBoolean(),
                portGroupIds = Set(UUID.randomUUID, UUID.randomUUID))
            val device = Port(port,
                              PortInactive,
                              Lists.newArrayList(port.getInboundFilterId.asJava),
                              Lists.newArrayList(port.getOutboundFilterId.asJava))

            device shouldBeDeviceOf port
            device.deviceTag should not be null
        }
    }
}
