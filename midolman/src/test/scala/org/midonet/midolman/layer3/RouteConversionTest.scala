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

package org.midonet.midolman.layer3

import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.FeatureSpec
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.Topology.{Route => TopologyRoute}
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.midolman.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class RouteConversionTest extends FeatureSpec with TopologyBuilder
                          with TopologyMatchers {

    private val random = new Random()

    feature("Conversion for route") {
        scenario("Test conversion from message with BLACKHOLE next hop") {
            val route = createRoute(
                nextHop = NextHop.BLACKHOLE,
                nextHopGateway = Some(IPv4Addr.random.toString),
                weight = Some(random.nextInt()),
                attributes = Some(random.nextString(10)))
            val device = ZoomConvert.fromProto(route, classOf[Route])

            device shouldBeDeviceOf route
        }

        scenario("Test conversion from message with REJECT next hop") {
            val route = createRoute(
                nextHop = NextHop.REJECT,
                nextHopGateway = Some(IPv4Addr.random.toString),
                weight = Some(random.nextInt()),
                attributes = Some(random.nextString(10)))
            val device = ZoomConvert.fromProto(route, classOf[Route])

            device shouldBeDeviceOf route
        }

        scenario("Test conversion from message with PORT next hop") {
            val route = createRoute(
                nextHop = NextHop.PORT,
                nextHopGateway = Some(IPv4Addr.random.toString),
                weight = Some(random.nextInt()),
                attributes = Some(random.nextString(10)))
            val device = ZoomConvert.fromProto(route, classOf[Route])

            device shouldBeDeviceOf route
        }

        scenario("Test conversion from message with LOCAL next hop") {
            val route = createRoute(
                nextHop = NextHop.LOCAL,
                nextHopGateway = Some(IPv4Addr.random.toString),
                weight = Some(random.nextInt()),
                attributes = Some(random.nextString(10)))
            val device = ZoomConvert.fromProto(route, classOf[Route])

            device shouldBeDeviceOf route
        }

        scenario("Test conversion forbidden to message") {

            val route = new Route()

            intercept[ConvertException] {
                ZoomConvert.toProto(route, classOf[TopologyRoute])
            }
        }
    }
}
