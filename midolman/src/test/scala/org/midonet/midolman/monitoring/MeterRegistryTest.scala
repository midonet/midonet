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
package org.midonet.midolman.monitoring

import java.util.UUID

import org.midonet.odp.flows.FlowStats
import org.midonet.odp.{FlowMatches, Packet, FlowMatch}
import org.midonet.packets.{IPv4Addr, MAC, Ethernet}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.{MeterTag, FlowTag}
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class MeterRegistryTest extends FeatureSpec with Matchers {

    val deviceA: MeterTag = FlowTagger.tagForDevice(UUID.randomUUID()).asInstanceOf[MeterTag]
    val deviceB: MeterTag = FlowTagger.tagForDevice(UUID.randomUUID()).asInstanceOf[MeterTag]
    val commonDevice: MeterTag = FlowTagger.tagForDevice(UUID.randomUUID()).asInstanceOf[MeterTag]

    def nonMeterRandomTag = FlowTagger.tagForBroadcast(UUID.randomUUID())

    val packetA: Ethernet = { eth addr MAC.random() -> MAC.random() } <<
                            { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
                            { udp ports 4500 ---> 500 } <<
                            { payload("foo") }
    val packetB: Ethernet = { eth addr MAC.random() -> MAC.random() } <<
                            { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
                            { icmp.echo.request id 23 seq 32}


    val matchA = FlowMatches.fromEthernetPacket(packetA)
    val matchB = FlowMatches.fromEthernetPacket(packetB)

    val metersA: List[MeterTag] = List(deviceA, commonDevice)
    val metersB: List[MeterTag] = List(deviceB, commonDevice)

    val tagsA: Array[FlowTag] = (nonMeterRandomTag :: metersA).toArray
    val tagsB: Array[FlowTag] = (nonMeterRandomTag :: metersB).toArray

    feature("Meter registry") {
        scenario("registers new meters") {
            val registry = new MeterRegistry(10)

            registry.trackFlow(matchA, tagsA)
            registry.meters should have size 2
            for (meter <- metersA) {
                registry.meters.keySet should contain (meter.meterName)
            }

            registry.trackFlow(matchB, tagsB)
            registry.meters should have size 3
            for (meter <- metersB) {
                registry.meters.keySet should contain (meter.meterName)
            }

            for (stats <- registry.meters.values) {
                stats.packets should === (0)
                stats.bytes should === (0)
            }
        }

        scenario("tracks stats for a single flow, N meters") {
            val registry = new MeterRegistry(10)
            registry.trackFlow(matchA, tagsA)

            val stats = new FlowStats()
            for (i <- 1 to 10) {
                stats.packets = i
                stats.bytes = i * 100
                registry.updateFlow(matchA, stats)

                for (meter <- registry.meters.values) {
                    meter.packets should === (i)
                    meter.bytes should === (i * 100)
                }
            }
        }

        scenario("forgets flows") {
            val registry = new MeterRegistry(10)
            registry.trackFlow(matchA, tagsA)

            val fixedPackets = 5
            val fixedBytes = 55

            val stats = new FlowStats(fixedPackets, fixedBytes)
            registry.updateFlow(matchA, stats)
            registry.forgetFlow(matchA)

            stats.packets = 245
            stats.bytes = 1235
            registry.updateFlow(matchA, stats)
            for (meter <- registry.meters.values) {
                meter.packets should === (fixedPackets)
                meter.bytes should === (fixedBytes)
            }
        }

        scenario("tracks stats for two flows, overlapping meters") {
            val registry = new MeterRegistry(10)
            registry.trackFlow(matchA, tagsA)
            registry.trackFlow(matchB, tagsB)

            val stats = new FlowStats()
            for (i <- 1 to 10) {
                stats.packets = i
                stats.bytes = i * 100
                registry.updateFlow(matchA, stats)
                registry.updateFlow(matchB, stats)

                registry.meters(commonDevice.meterName).packets should === (i * 2)
                registry.meters(commonDevice.meterName).bytes should === (i * 200)
                registry.meters(deviceA.meterName).packets should === (i)
                registry.meters(deviceA.meterName).bytes should === (i * 100)
                registry.meters(deviceB.meterName).packets should === (i)
                registry.meters(deviceB.meterName).bytes should === (i * 100)

            }
        }
    }
}
