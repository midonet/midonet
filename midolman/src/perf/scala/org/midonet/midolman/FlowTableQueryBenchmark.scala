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

import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import java.util.{ArrayList, Random}

import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.{Flow, FlowMatch, FlowMatches}
import org.midonet.sdn.flows.WildcardFlow
import org.midonet.util.functors.Callback0
import org.openjdk.jmh.annotations.{Setup => JmhSetup, _}

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

    val wcMatches = new Array[FlowMatch](numFlows)

    @JmhSetup
    def setup() {
        val rand = new Random()
        for (i <- 0 until numFlows) {
            val flowMatch = FlowMatches.generateFlowMatch(rand)
            wcMatches(i) = flowMatch
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
