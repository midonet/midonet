/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.ArrayList
import java.util.concurrent.TimeUnit

import scala.util.Random

import org.openjdk.jmh.annotations.{Setup => JmhSetup, Benchmark, Scope, State, Threads, Fork, Measurement, Warmup, OutputTimeUnit, Mode, BenchmarkMode}

import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.{FlowMatch, Flow}
import org.midonet.odp.flows.{IpProtocol, FlowKeys, FlowKey}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.flows.WildcardFlow.WildcardFlowImpl
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.Callback0

object FlowTableQueryBenchmark {
    val numFlows = 10000

    @State(Scope.Thread)
    sealed class ThreadIndex {
        var value: Long = new Random().nextInt(numFlows)

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
                new WildcardFlowImpl(wcMatches(i)),
                new Flow(flowMatch),
                new ArrayList[Callback0](), // No callbacks
                Set()) // No tags
        }
    }

    @Benchmark
    def queryFlowTable(index: ThreadIndex) =
        FlowController.queryWildcardFlowTable(wcMatches(index.getAndIncrement()))
}
