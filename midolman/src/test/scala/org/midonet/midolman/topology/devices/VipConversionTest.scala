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
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Vip.SessionPersistence
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.midolman.simulation.VIP
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class VipConversionTest extends FeatureSpec
                        with Matchers
                        with TopologyBuilder
                        with TopologyMatchers {

    private val random = new Random()

    feature("Conversion for VIP") {
        scenario("From Protocol Buffer message") {
            val proto = createVip(adminStateUp = Some(true),
                                  loadBalancerId = Some(UUID.randomUUID()),
                                  poolId = Some(UUID.randomUUID()),
                                  address = Some(IPv4Addr.random),
                                  protocolPort = Some(random.nextInt()),
                                  sessionPersistence =
                                      Some(SessionPersistence.SOURCE_IP))
            val zoomObj = ZoomConvert.fromProto(proto, classOf[VIP])
            zoomObj shouldBeDeviceOf proto
        }

        scenario("To Protocol Buffer message") {
            val zoomObj = new VIP(id = UUID.randomUUID(),
                                  adminStateUp = true,
                                  poolId = UUID.randomUUID(),
                                  address = IPv4Addr.random,
                                  protocolPort = random.nextInt(),
                                  sessionPersistence =
                                      VipSessionPersistence.SOURCE_IP,
                                  loadBalancerId = UUID.randomUUID())
            val proto = ZoomConvert.toProto(zoomObj, classOf[Topology.Vip])
            zoomObj shouldBeDeviceOf proto
        }
    }
}
