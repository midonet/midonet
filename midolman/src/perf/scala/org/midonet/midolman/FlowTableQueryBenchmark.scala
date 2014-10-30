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

package org.midonet.midolman

import java.util.{Random, ArrayList}
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.annotations.{Setup => JmhSetup}

import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.flows.{FlowKey, FlowKeys, IpProtocol}
import org.midonet.odp.{Flow, FlowMatch}
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.functors.Callback0

object FlowTableQueryBenchmark {
    val numFlows = 10000

    @State(Scope.Thread)
    sealed class ThreadIndex {
        var value: Long = ThreadLocalRandom.current().nextInt(numFlows)

        def getAndIncrement(): Int = {
            val res = value
            value += 1
            (res % numFlows).asInstanceOf[Int]
        }
    }
}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@Threads(Threads.MAX)
@State(Scope.Benchmark)
class FlowTableQueryBenchmark extends MidolmanBenchmark {
    import FlowTableQueryBenchmark._

    registerActors(FlowController -> (() => new FlowController
                                            with MessageAccumulator))

    val wcMatches = new Array[WildcardMatch](numFlows)

    def generateFlowMatch(rand: Random): FlowMatch = {
        val keys = new ArrayList[FlowKey]()
        if (rand.nextInt(4) == 0) {
            keys.add(FlowKeys.tunnel(rand.nextLong(), generateIp(rand).toInt,
                                     generateIp(rand).toInt))
        }
        keys.add(FlowKeys.ethernet(generateMac(rand), generateMac(rand)))
        if (rand.nextInt(3) == 0) {
            val proto = if (rand.nextBoolean()) IpProtocol.TCP else IpProtocol.UDP
            keys.add(FlowKeys.ipv4(generateIp(rand), generateIp(rand), proto))
            val src = rand.nextInt() & 0xFFFF
            val dst = rand.nextInt() & 0xFFFF
            keys.add(if (proto == IpProtocol.TCP) {
                        FlowKeys.tcp(src, dst)
                    } else {
                        FlowKeys.udp(src, dst)
                    })
        }
        new FlowMatch(keys)
    }

    def generateIp(rand: Random) = IPv4Addr.fromInt(rand.nextInt())

    def generateMac(rand: Random) = {
        val bytes = new Array[Byte](6)
        rand.nextBytes(bytes)
        bytes
    }

    def generatePort(rand: Random): Short = rand.nextInt().asInstanceOf[Short]

    @JmhSetup
    def setup() {
        val rand = new Random()
        for (i <- 0 until numFlows) {
            val flowMatch = generateFlowMatch(rand)
            wcMatches(i) = WildcardMatch.fromFlowMatch(flowMatch)
            FlowController ! AddWildcardFlow(
                WildcardFlow(wcMatches(i)),
                new Flow(flowMatch),
                new ArrayList[Callback0](), // No callbacks
                Set()) // No tags
        }
    }

    @Benchmark
    def queryFlowTable(index: ThreadIndex) =
        FlowController.queryWildcardFlowTable(wcMatches(index.getAndIncrement()))
}
