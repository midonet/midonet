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

package org.midonet.midolman.state

import java.util.{ArrayList, Collections, List, Objects, UUID}

import org.slf4j.{LoggerFactory,MDC}
import scala.collection.JavaConverters._

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.odp.FlowMatch

import org.midonet.packets.{MAC, IPAddr}

import org.midonet.sdn.state.FlowStateTransaction

object TraceState {
    val log = LoggerFactory.getLogger(classOf[TraceState])

    val TraceTunnelKeyMask = 0x80000
    val TraceLoggingContextKey = "traceIds"

    def traceBitPresent(tunnelId: Long): Boolean = {
        (tunnelId & TraceTunnelKeyMask) != 0
    }

    object TraceKey {
        def fromFlowMatch(flowMatch: FlowMatch): TraceKey = {
            flowMatch.doNotTrackSeenFields
            val key = TraceKey(flowMatch.getEthSrc, flowMatch.getEthDst,
                               flowMatch.getEtherType,
                               flowMatch.getNetworkSrcIP,
                               flowMatch.getNetworkDstIP,
                               flowMatch.getNetworkProto,
                               flowMatch.getNetworkTOS,
                               flowMatch.getSrcPort,
                               flowMatch.getDstPort)
            flowMatch.doTrackSeenFields
            key
        }
    }

    case class TraceKey(ethSrc: MAC, ethDst: MAC, etherType: Short,
                        networkSrc: IPAddr, networkDst: IPAddr,
                        networkProto: Byte, networkTOS: Byte,
                        srcPort: Int, dstPort: Int)
            extends FlowStateKey {
    }

    class TraceContext {
        var flowTraceId: UUID = null
        val requests: List[UUID] = new ArrayList[UUID](1)

        def enabled(): Boolean = flowTraceId != null
        def enable(flowTraceId: UUID = UUID.randomUUID): TraceContext = {
            this.flowTraceId = flowTraceId
            this
        }
        def clear(): Unit = {
            flowTraceId = null
            requests.clear()
        }
        def reset(other: TraceContext): Unit = {
            clear
            flowTraceId = other.flowTraceId
            requests.addAll(other.requests)
        }

        def addRequest(request: UUID): Boolean = requests.add(request)
        def containsRequest(request: UUID): Boolean = requests.contains(request)

        override def hashCode(): Int = Objects.hashCode(flowTraceId, requests)
        override def equals(other: Any): Boolean = other match {
            case that: TraceContext => {
                Objects.equals(flowTraceId, that.flowTraceId) &&
                Objects.equals(requests, that.requests)
            }
            case _ => false
        }

        override def toString: String =
            s"Trace[flowId=$flowTraceId,requests=$requests]"
    }
}

trait TraceState extends FlowState { this: PacketContext =>
    import org.midonet.midolman.state.TraceState._

    var tracing: List[UUID] = new ArrayList[UUID]
    var traceTx: FlowStateTransaction[TraceKey, TraceContext] = _
    val traceContext: TraceContext = new TraceContext

    private var clearEnabled = true

    def prepareForSimulationWithTracing(): Unit = {
        // clear non-trace info
        clearEnabled = false
        clear()
        clearEnabled = true

        runFlowRemovedCallbacks()
    }

    abstract override def clear(): Unit = {
        super.clear()

        if (clearEnabled) {
            tracing.clear()
            traceTx.flush()
        }
    }

    def tracingContext : String = traceContext.toString

    def tracingEnabled: Boolean = traceContext.enabled

    def tracingEnabled(traceRequestId: UUID): Boolean = {
        traceContext.enabled && traceContext.containsRequest(traceRequestId)
    }

    def enableTracing(traceRequestId: UUID): Unit = {
        if (!traceContext.enabled) {
            val key = TraceKey.fromFlowMatch(origMatch)
            val storedCtx = traceTx.get(key)
            if (storedCtx == null) {
                traceContext.enable()
                traceTx.putAndRef(key, traceContext)
            } else {
                traceContext.reset(storedCtx)
            }
        }
        traceContext.addRequest(traceRequestId)
        log = PacketContext.traceLog
        MDC.put(TraceLoggingContextKey, tracingContext)
    }

    def traceFlowId(): UUID =
        if (traceContext.enabled) {
            traceContext.flowTraceId
        } else {
            null
        }

    // setkey actions will modify the packet before it hits the egress
    // host. Add a tracekey that will match this packet
    def addModifiedTraceKeys(): Unit = {
        if (tracingEnabled) {
            val key = TraceKey.fromFlowMatch(origMatch)
            val modKey = TraceKey.fromFlowMatch(wcmatch)
            if (key != modKey) {
                traceTx.putAndRef(modKey, traceContext)
            }
        }
    }

    def setTraceTunnelBit(key: Long): Long = {
        key | TraceTunnelKeyMask
    }

    def hasTraceTunnelBit(fmatch: FlowMatch): Boolean = {
        TraceState.traceBitPresent(fmatch.getTunnelKey) &&
            fmatch.getTunnelKey != FlowStatePackets.TUNNEL_KEY
    }

    def stripTraceBit(matches: FlowMatch*): Unit = {
        matches.foreach(m => {
                            m.doNotTrackSeenFields
                            m.setTunnelKey(
                                (m.getTunnelKey & ~TraceTunnelKeyMask))
                            m.doTrackSeenFields
                        })
    }

    def enableTracingFromTable(): Unit = {
        val key = TraceKey.fromFlowMatch(origMatch)
        val storedContext = traceTx.get(key)
        if (storedContext == null) {
            log.warn("Couldn't find trace state for {}", key)
            traceContext.enable()
        } else {
            traceContext.reset(storedContext)
        }
        log = PacketContext.traceLog
        MDC.put(TraceLoggingContextKey, tracingContext)
    }
}
