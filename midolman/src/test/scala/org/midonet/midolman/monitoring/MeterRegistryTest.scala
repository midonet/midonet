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

import java.util.concurrent.atomic.AtomicInteger
import java.util.{ArrayList, UUID}

import scala.collection.JavaConverters._
import com.google.monitoring.runtime.instrumentation.AllocationRecorder
import com.google.monitoring.runtime.instrumentation.Sampler
import org.scalatest.{FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import org.junit.Assert
import org.junit.runner.RunWith
import org.midonet.midolman.management.Metering
import org.midonet.odp.flows.FlowStats
import org.midonet.odp.FlowMatches
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.MeterTag
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

@RunWith(classOf[JUnitRunner])
class MeterRegistryTest extends FeatureSpec with Matchers {

    val deviceA: MeterTag = FlowTagger.tagForBridge(UUID.randomUUID()).asInstanceOf[MeterTag]
    val deviceB: MeterTag = FlowTagger.tagForRouter(UUID.randomUUID()).asInstanceOf[MeterTag]
    val commonDevice: MeterTag = FlowTagger.tagForPort(UUID.randomUUID()).asInstanceOf[MeterTag]

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

    val tagsA = new ArrayList((nonMeterRandomTag :: metersA).asJava)
    val tagsB = new ArrayList((nonMeterRandomTag :: metersB).asJava)

    val FIRST_PKT_SIZE = 237

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

            for (stats <- registry.meters.values.asScala) {
                stats.packets should === (0)
                stats.bytes should === (0)
            }
        }

        scenario("tracks stats for a single flow, N meters") {
            val registry = new MeterRegistry(10)
            registry.trackFlow(matchA, tagsA)
            registry.recordPacket(FIRST_PKT_SIZE, tagsA)

            val stats = new FlowStats()
            for (i <- 1 to 10) {
                stats.packets = i
                stats.bytes = i * 100
                registry.updateFlow(matchA, stats)

                for (meter <- registry.meters.values.asScala) {
                    meter.packets should === (i + 1)
                    meter.bytes should === (i * 100 + FIRST_PKT_SIZE)
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
            for (meter <- registry.meters.values.asScala) {
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

                registry.meters.get(commonDevice.meterName).packets should === (i * 2)
                registry.meters.get(commonDevice.meterName).bytes should === (i * 200)
                registry.meters.get(deviceA.meterName).packets should === (i)
                registry.meters.get(deviceA.meterName).bytes should === (i * 100)
                registry.meters.get(deviceB.meterName).packets should === (i)
                registry.meters.get(deviceB.meterName).bytes should === (i * 100)

            }
        }

        val allocsNumber: AtomicInteger = new AtomicInteger(0)
        val allocsSize: AtomicInteger = new AtomicInteger(0)

        def assertZeroAlloc(runnable: Runnable, maxAllocsNumber: Int = 0, maxAllocsSize: Int = 0): Unit = {

            val sampler = new Sampler() {
                def sampleAllocation(count: Int, desc: String, newObject: Any, size: Long) = {
                    allocsNumber.incrementAndGet()
                    allocsSize.addAndGet(size.toInt)
                }
            }

            AllocationRecorder.addSampler(sampler)
            var initialAllocations = allocsNumber.get()
            var initialAllocationsSize = allocsSize.get()
            try {
                AllocationRecorder.addSampler(sampler)
                runnable.run()
                AllocationRecorder.removeSampler(sampler)
                AllocationRecorder.addSampler(sampler)
                initialAllocations = allocsNumber.get()
                initialAllocationsSize = allocsSize.get()
                runnable.run()
                val allocations = allocsNumber.get() - initialAllocations
                val allocationsSize = allocsSize.get() - initialAllocationsSize
                Assert.assertTrue("Check allocations number (" + allocations + " <= " + maxAllocsNumber,
                    allocations <= maxAllocsNumber)
                Assert.assertTrue("Check allocations size (" + allocationsSize + " <= " + maxAllocsSize,
                    allocationsSize <= maxAllocsSize)
            } catch{
                case e: Exception => {
                    AllocationRecorder.removeSampler(sampler)
                    Assert.fail("A exception was catch ")
                    return
                }
            }
            AllocationRecorder.removeSampler(sampler)
        }

        scenario("append long with zero allocation") {
            val buffer = new StringBuilder(1000)
            val packets : Long = 10

            val runnable = new Runnable {
                override def run(): Unit = {
                    org.midonet.util.StringUtil.append(buffer, packets)
                    buffer.delete(0, buffer.capacity)
                }
            }

            assertZeroAlloc(runnable)
        }

        scenario("append a meter with zero allocation v3") {
            val buffer = new StringBuilder(1000)
            val id : Int = 0
            val key : String = "hola"
            val packets : Long = 10
            val bytes : Long = 12
            val delim : Char = ' '

            val runnable = new Runnable {
                override def run(): Unit = {
                    Metering.serializeMeter(key, packets, bytes, buffer, delim)
                    buffer.delete(0, buffer.capacity)
                }
            }

            assertZeroAlloc(runnable)
        }

        scenario("append a table of the meters with zero allocation") {
            val registry = new MeterRegistry(10)
            registry.trackFlow(matchA, tagsA)
            registry.trackFlow(matchB, tagsB)

            val stats = new FlowStats()
            for (i <- 1 to 10) {
                stats.packets = i
                stats.bytes = i * 100
                registry.updateFlow(matchA, stats)
                registry.updateFlow(matchB, stats)
            }

            Metering.registerAsMXBean(registry)

            val buffer = new StringBuilder(10000)

            val runnable = new Runnable {
                override def run(): Unit = {
                    Metering.toTextTable(buffer, ' ')
                    buffer.delete(0, buffer.capacity)
                }
            }

            assertZeroAlloc(runnable, maxAllocsNumber = 1, maxAllocsSize = 56)
        }
    }
}
