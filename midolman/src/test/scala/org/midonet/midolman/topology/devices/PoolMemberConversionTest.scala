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
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Topology
import org.midonet.midolman.simulation.PoolMember
import org.midonet.midolman.state.l4lb
import org.midonet.midolman.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class PoolMemberConversionTest extends FeatureSpec with Matchers
                               with TopologyBuilder with TopologyMatchers {

    private val random = new Random()

    feature("Conversion for pool members") {
        scenario("Test conversion from Protocol Buffers message") {
            val poolMember = createPoolMember(
                adminStateUp = Some(random.nextBoolean()),
                poolId = Some(UUID.randomUUID),
                status = Some(LBStatus.ACTIVE),
                address = Some(IPv4Addr.random),
                port = Some(random.nextInt()),
                weight = Some(random.nextInt()))
            val device = ZoomConvert.fromProto(poolMember, classOf[PoolMember])

            device shouldBeDeviceOf poolMember
        }

        scenario("Test conversion to Protocol Buffers message") {
            val poolMember = new PoolMember(UUID.randomUUID,
                                            random.nextBoolean(),
                                            l4lb.LBStatus.ACTIVE,
                                            IPv4Addr.random,
                                            random.nextInt(),
                                            random.nextInt())
            val proto = ZoomConvert.toProto(poolMember,
                                            classOf[Topology.PoolMember])

            poolMember shouldBeDeviceOf proto
        }
    }
}
