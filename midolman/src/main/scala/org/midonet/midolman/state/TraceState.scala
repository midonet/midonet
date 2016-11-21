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

import java.util.{ArrayList, List, Objects, UUID}

import com.datastax.driver.core.utils.UUIDs

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.TunnelKeys.TraceBit
import org.midonet.midolman.logging.FlowTracingContext
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.odp.FlowMatch
import org.midonet.packets.TraceState.{TraceKeyAllocator, TraceKeyStore}
import org.midonet.packets.{MAC, IPAddr, ICMP}
import org.midonet.sdn.state.FlowStateTransaction

object TraceState {
    val log = LoggerFactory.getLogger(classOf[TraceState])

    object TraceKey extends TraceKeyAllocator[TraceKey] {
        def fromFlowMatch(flowMatch: FlowMatch): TraceKey = {
            flowMatch.doNotTrackSeenFields()
            val srcPort =
                if (flowMatch.getNetworkProto == ICMP.PROTOCOL_NUMBER)
                    flowMatch.getIcmpIdentifier
                else
                    flowMatch.getSrcPort
            val dstPort =
                if (flowMatch.getNetworkProto == ICMP.PROTOCOL_NUMBER)
                    flowMatch.getIcmpIdentifier
                else
                    flowMatch.getDstPort

            val key = TraceKey(flowMatch.getEthSrc, flowMatch.getEthDst,
                               flowMatch.getEtherType,
                               flowMatch.getNetworkSrcIP,
                               flowMatch.getNetworkDstIP,
                               flowMatch.getNetworkProto,
                               srcPort, dstPort)
            flowMatch.doTrackSeenFields()
            key
        }

        def apply(ethSrc: MAC, ethDst: MAC, etherType: Short,
                  networkSrc: IPAddr, networkDst: IPAddr,
                  networkProto: Byte, srcPort: Int, dstPort: Int): TraceKey =
            new TraceKeyStore(
                ethSrc, ethDst, etherType,
                networkSrc, networkDst,
                networkProto, srcPort, dstPort) with FlowStateKey

    }

    type TraceKey = TraceKeyStore with FlowStateKey

    class TraceContext(var flowTraceId: UUID = UUIDs.timeBased()) {
        var _enabled = false
        val requests: List[UUID] = new ArrayList[UUID](1)

        def enabled: Boolean = _enabled
        def enable(): TraceContext = {
            _enabled = true
            this
        }
        def disable(): TraceContext = {
            _enabled = false
            this
        }

        def reset(): Unit = {
            _enabled = false
            flowTraceId = UUIDs.timeBased()
            requests.clear()
        }
        def reset(other: TraceContext): Unit = {
            reset()
            flowTraceId = other.flowTraceId
            _enabled = other._enabled
            requests.addAll(other.requests)
        }

        def addRequest(request: UUID): Boolean =
            if (!containsRequest(request)) {
                requests.add(request)
                true
            } else {
                false
            }

        def containsRequest(request: UUID): Boolean = requests.contains(request)

        override def hashCode(): Int = Objects.hashCode(flowTraceId, requests)
        override def equals(other: Any): Boolean = other match {
            case that: TraceContext =>
                Objects.equals(flowTraceId, that.flowTraceId) &&
                Objects.equals(requests, that.requests)
            case _ => false
        }

        override def toString: String =
            s"Trace[flowId=$flowTraceId,requests=$requests]"
    }
}

trait TraceState extends FlowState { this: PacketContext =>
    import org.midonet.midolman.state.TraceState._

    // noone should write to this transaction
    var traceTxReadOnly: FlowStateTransaction[TraceKey, TraceContext] = _
    val traceContext: TraceContext = new TraceContext

    def tracingContext : String = traceContext.toString

    def tracingEnabled: Boolean = traceContext.enabled

    override def clear() {
        super.clear()
        traceContext.disable()
    }

    def resetTraceState(): Unit = {
        traceTxReadOnly = null
        traceContext.reset()
    }

    def prepareForSimulationWithTracing(): Unit = {
        clear()
        traceContext.enable()
    }

    def tracingEnabled(traceRequestId: UUID): Boolean = {
        traceContext.enabled && traceContext.containsRequest(traceRequestId)
    }

    /**
      * Enable tracing for a context and add traceRequestId to the
      * list of trace requests for the context. Returns true if the trace
      * request is not already in the list of requests. This can happen
      * in the case where tracing is enabled, but then a piece of topology
      * is missing later on, so the context is cleared.
      */
    def enableTracing(traceRequestId: UUID): Boolean = {
        val key = TraceKey.fromFlowMatch(origMatch)
        if (!traceContext.enabled) {
            traceContext.enable()
        }
        val ret = traceContext.addRequest(traceRequestId)
        log = PacketContext.traceLog
        FlowTracingContext.updateContext(traceContext.requests,
                                         traceContext.flowTraceId, key)
        ret
    }

    def traceFlowId(): UUID =
        if (traceContext.enabled) {
            traceContext.flowTraceId
        } else {
            null
        }

    // setkey actions will modify the packet before it hits the egress
    // host. the trace
    def traceKeyForEgress = TraceKey.fromFlowMatch(wcmatch)

    def setTraceTunnelBit(key: Long): Long = {
        TraceBit.set(key.toInt)
    }

    def hasTraceTunnelBit: Boolean = {
        TraceBit.isSet(origMatch.getTunnelKey.toInt) &&
                origMatch.getTunnelKey != FlowStateAgentPackets.TUNNEL_KEY
    }

    def stripTraceBit(m: FlowMatch): Unit = {
        m.doNotTrackSeenFields()
        m.setTunnelKey(TraceBit.clear(m.getTunnelKey.toInt))
        m.doTrackSeenFields()
    }

    def enableTracingOnEgress(): Unit = {
        val key = TraceKey.fromFlowMatch(origMatch)
        val storedContext = traceTxReadOnly.get(key)
        log = PacketContext.traceLog
        if (storedContext == null) {
            log.warn("Couldn't find trace state for {}", key)
            traceContext.enable()
        } else {
            traceContext.reset(storedContext)
        }
        FlowTracingContext.updateContext(traceContext.requests,
                                         traceContext.flowTraceId, key)
        stripTraceBit(wcmatch)
    }
}
