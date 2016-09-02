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

import java.util.{Collections, UUID}

import scala.util.Random

import com.google.common.collect.Lists

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.state.PortStateStorage.PortInactive
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class PortTest extends FeatureSpec with Matchers with TopologyBuilder
               with TopologyMatchers {

    private val random = new Random()

    feature("Test port conversion") {
        scenario("Unknown port type") {
            val port = createBridgePort()
            intercept[ConvertException] {
                Port(port, PortInactive, Collections.emptyList(),
                     Collections.emptyList())
            }
        }

        scenario("Bridge port") {
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

        scenario("Router port") {
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

        scenario("VXLAN port") {
            val port = createVxLanPort(
                bridgeId = Some(UUID.randomUUID),
                inboundFilterId = Some(UUID.randomUUID),
                outboundFilterId = Some(UUID.randomUUID),
                tunnelKey = random.nextLong(),
                peerId = Some(UUID.randomUUID),
                vifId = Some(UUID.randomUUID),
                vtepId = Some(UUID.randomUUID()),
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

        scenario("Bridge port with VPP binding") {
            val port = createBridgePort(
                bridgeId = Some(UUID.randomUUID()),
                vppBinding = Some(createVppBinding(random.nextString(3))))
            val device = Port(port, PortInactive, Collections.emptyList(),
                              Collections.emptyList())

            device shouldBeDeviceOf port
        }

        scenario("Router port with VPP binding") {
            val port = createRouterPort(
                routerId = Some(UUID.randomUUID()),
                vppBinding = Some(createVppBinding(random.nextString(3))))
            val device = Port(port, PortInactive, Collections.emptyList(),
                              Collections.emptyList())

            device shouldBeDeviceOf port
        }

    }
}
