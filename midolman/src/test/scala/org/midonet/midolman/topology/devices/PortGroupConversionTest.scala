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

package org.midonet.midolman.topology.devices

import java.util.UUID

import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.midolman.simulation.PortGroup
import org.midonet.midolman.topology.{TopologyMatchers, TopologyBuilder}
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class PortGroupConversionTest extends FeatureSpec with Matchers
                              with TopologyBuilder with TopologyMatchers {

    private val random = new Random()

    feature("Conversion for port groups") {
        scenario("Test conversion from Protocol Buffers message") {
            val portGroup = createPortGroup(
                name = Some(random.nextString(10)),
                tenantId = Some(random.nextString(10)),
                stateful = Some(random.nextBoolean()),
                portIds = Set(UUID.randomUUID, UUID.randomUUID))
            val device = ZoomConvert.fromProto(portGroup, classOf[PortGroup])

            device shouldBeDeviceOf portGroup
            device.deviceTag shouldBe FlowTagger.tagForDevice(device.id)
        }

        scenario("Test conversion to Protocol Buffers message") {

            val portGroup = new PortGroup(UUID.randomUUID,
                                          random.nextString(10),
                                          random.nextBoolean(),
                                          Set(UUID.randomUUID, UUID.randomUUID))
            val proto = ZoomConvert.toProto(portGroup,
                                            classOf[Topology.PortGroup])

            portGroup shouldBeDeviceOf proto
        }
    }
}
