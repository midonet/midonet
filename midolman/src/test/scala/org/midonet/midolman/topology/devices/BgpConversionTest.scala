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
import org.scalatest.{FlatSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.midolman.topology.routing.Bgp
import org.midonet.midolman.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class BgpConversionTest extends FlatSpec with Matchers
                        with TopologyBuilder with TopologyMatchers {

    private val random = new Random()

    "BGP" should "convert from Protocol Buffers" in {
        val proto = createBGP(localAs = Some(random.nextInt()),
                            peerAs = Some(random.nextInt()),
                            peerAddress = Some(IPv4Addr.random),
                            portId = Some(UUID.randomUUID),
                            bgpRouteIds = Set(UUID.randomUUID, UUID.randomUUID))
        val bgp = ZoomConvert.fromProto(proto, classOf[Bgp])

        bgp shouldBeDeviceOf proto
    }

}
