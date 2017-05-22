/*
 * Copyright 2017 Midokura SARL
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

import java.io.{BufferedWriter, StringWriter}
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

@RunWith(classOf[JUnitRunner])
class MeterRegistryAllocationTest extends FeatureSpec with Matchers {
    val commonDevice: MeterTag = FlowTagger.tagForPort(UUID.randomUUID()).asInstanceOf[MeterTag]
    def nonMeterRandomTag = FlowTagger.tagForBroadcast(UUID.randomUUID())

    feature("meter register allocation") {
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
            val buffer = new BufferedWriter(new StringWriter(10000))
            val packets : Long = 10

            val runnable = new Runnable {
                override def run(): Unit = {
                    org.midonet.util.StringUtil.append(buffer, packets)
                }
            }

            assertZeroAlloc(runnable)
        }

        scenario("append a meter with zero allocation") {
            val key : String = "abcd"
            val packets : Long = 10
            val bytes : Long = 12
            val delim : Char = ' '

            val writer = new StringWriter(10000)
            val bufferedWriter = new BufferedWriter(writer)

            val runnable = new Runnable {
                override def run(): Unit = {
                    writer.getBuffer().delete(0, writer.getBuffer().length)
                    Metering.serializeMeter(key, packets, bytes, bufferedWriter, delim)
                    bufferedWriter.flush()
                }
            }
            assertZeroAlloc(runnable) // this executes twice the runnable

            val result = writer.getBuffer.toString
            val expected = key + delim + packets + delim + bytes + "\n"

            assert(result.equals(expected), "'" + result + "' not equals to expected '" + expected + "'")
        }

        scenario("append a table of the meters with minimal allocation") {
            val registry = MeterRegistry.newOnHeap(10)
            for (i <- 1 to 100) {
                val device: MeterTag = FlowTagger.tagForBridge(UUID.randomUUID()).asInstanceOf[MeterTag]
                val packet: Ethernet = { eth addr MAC.random() -> MAC.random() } <<
                  { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
                  { udp ports 4500 ---> 500 } <<
                  { payload("foo") }
                val matchPacket = FlowMatches.fromEthernetPacket(packet)
                val meters: List[MeterTag] = List(device, commonDevice)
                val tagsA = new ArrayList((nonMeterRandomTag :: meters).asJava)
                registry.trackFlow(matchPacket, tagsA)
                val stats = new FlowStats()
                stats.packets = i * 10
                stats.bytes = i * 1000
                registry.updateFlow(matchPacket, stats)
            }

            Metering.registerAsMXBean(registry)

            val buffer = new BufferedWriter(new StringWriter(10000))

            val runnable = new Runnable {
                override def run(): Unit = {
                    Metering.toTextTable(buffer, ' ')
                }
            }

            assertZeroAlloc(runnable, maxAllocsNumber = 1, maxAllocsSize = 56)
        }
    }
}
