/*
 * Copyright 2018 Midokura SARL
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
package org.midonet.midolman.management

import java.io.{BufferedWriter,StringWriter}
import java.util.{ArrayList, UUID}

import scala.collection.JavaConverters._

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FeatureSpec}

import org.slf4j.LoggerFactory

import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class MeteringTest extends FeatureSpec with Matchers {

    class PrometheusMeteringForTesting extends PrometheusMetering {
        var registries = List[MeterRegistry]()
        override def currentTimeMillis = 987654321
    }

    val deviceA = FlowTagger.tagForBridge(UUID.fromString("13b3e586-a075-4b0a-986a-5fb950ae0a6d"))
    val deviceB = FlowTagger.tagForRouter(UUID.fromString("27c9bc2e-2378-4319-aa3a-f59771639daf"))

    val metersA = List(deviceA)
    val metersB = List(deviceB)

    val tagsA = new ArrayList(metersA.asJava)
    val tagsB = new ArrayList(metersB.asJava)

    feature("metering") {
        scenario("prometheus compatible output") {
            val expected = """# HELP midonet_packets_total Total number of packets processed.
# TYPE midonet_packets_total counter
midonet_packets_total{key="meters:device:13b3e586-a075-4b0a-986a-5fb950ae0a6d",registry="0"} 1 987654321
midonet_packets_total{key="meters:device:27c9bc2e-2378-4319-aa3a-f59771639daf",registry="0"} 1 987654321
# HELP midonet_packets_bytes Total number of bytes processed.
# TYPE midonet_packets_bytes counter
midonet_packets_bytes{key="meters:device:13b3e586-a075-4b0a-986a-5fb950ae0a6d",registry="0"} 1234 987654321
midonet_packets_bytes{key="meters:device:27c9bc2e-2378-4319-aa3a-f59771639daf",registry="0"} 4321 987654321
"""
            val registry = MeterRegistry.newOnHeap(10)
            val matchA = new FlowMatch
            registry.trackFlow(matchA, tagsA)
            registry.recordPacket(1234, tagsA)
            registry.trackFlow(matchA, tagsB)
            registry.recordPacket(4321, tagsB)

            val meter = new PrometheusMeteringForTesting
            meter.registries :+= registry

            val s = new StringWriter()
            val writer = new BufferedWriter(s)
            meter.toPrometheusMetrics(writer)
            writer.flush()
            s.toString() shouldBe expected
        }
    }
}
