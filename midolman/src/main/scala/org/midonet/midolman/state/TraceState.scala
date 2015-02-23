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

import java.nio.ByteBuffer
import java.util.{ArrayList, Arrays, HashSet, List, Objects, Set, UUID, Collections}
import java.util.concurrent.ThreadLocalRandom

import org.slf4j.MDC
import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.odp.{FlowMatch,FlowMatches}
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowActionSetKey}
import org.midonet.odp.flows.{FlowKeyTunnel, FlowKey, FlowKeyInPort}
import org.midonet.packets.{Ethernet, IPv4Addr, IPv4, ICMP}
import org.midonet.packets.{TCP, UDP, BasePacket}
import org.midonet.sdn.state.FlowStateTransaction

object TraceState {
    val log = LoggerFactory.getLogger(classOf[TraceState])

    val TraceTunnelKeyMask = 0x80000
    val TraceLoggingContextKey = "traceIds"

    object TraceKey {
        val fields = FlowMatch.Field.values()
    }

    def traceBitPresent(tunnelId: Long): Boolean = {
        (tunnelId & TraceTunnelKeyMask) != 0
    }

    class TraceKey(origMatch: FlowMatch,
                   actions: List[FlowAction] = Collections.emptyList())
            extends FlowStateKey {
        val flowMatch: FlowMatch = new FlowMatch()
        flowMatch.reset(origMatch)
        flowMatch.fieldUnused(Field.InputPortNumber)
        flowMatch.fieldUnused(Field.TunnelDst)
        flowMatch.fieldUnused(Field.TunnelSrc)
        flowMatch.fieldUnused(Field.TunnelKey)

        var i = actions.size
        while (i > 0) {
            i -= 1
            actions.get(i) match {
                case k: FlowActionSetKey =>
                    flowMatch.addKey(k.getFlowKey)
            }
        }

        override def hashCode(): Int = Objects.hashCode(flowMatch)
        override def equals(other: Any): Boolean = other match {
            case that: TraceKey => flowMatch.equals(that.flowMatch)
            case _ => false
        }

        override def toString(): String =
            "TraceKey(" + flowMatch.toString + ")"

        private def checkField(field: Field, udp: UDP): Boolean =
            field match {
                case Field.SrcPort => flowMatch.getSrcPort.equals(
                    udp.getSourcePort)
                case Field.DstPort => flowMatch.getDstPort.equals(
                    udp.getDestinationPort)
                case _ => false
            }

        private def checkField(field: Field, tcp: TCP): Boolean =
            field match {
                case Field.SrcPort => flowMatch.getSrcPort.equals(
                    tcp.getSourcePort)
                case Field.DstPort => flowMatch.getDstPort.equals(
                    tcp.getDestinationPort)
                case _ => false
            }

        private def checkField(field: Field, icmp: ICMP): Boolean =
            field match {
                case Field.IcmpId => flowMatch.getIcmpIdentifier.equals(
                    icmp.getIdentifier)
                case Field.IcmpData => Arrays.equals(flowMatch.getIcmpData,
                                                     icmp.getData)
                case _ => false
            }

        private def checkField(field: Field, ip4: IPv4): Boolean =
            field match {
                case Field.NetworkSrc => flowMatch.getNetworkSrcIP.equals(
                    ip4.getSourceIPAddress())
                case Field.NetworkDst => {
                    log.info(s"${flowMatch.getNetworkDstIP} ${ip4.getDestinationIPAddress}")
                    flowMatch.getNetworkDstIP.equals(
                        ip4.getDestinationIPAddress)
                }
                case Field.NetworkProto => flowMatch.getNetworkProto.equals(
                    ip4.getProtocol)
                case Field.NetworkTTL => {
                    log.info(s"${flowMatch.getNetworkTTL} ${ip4.getTtl}")
                    flowMatch.getNetworkTTL.equals(
                        ip4.getTtl)
                }
                case Field.FragmentType => flowMatch.getIpFragmentType.value
                        .equals(ip4.getFlags)
                case Field.NetworkTOS => flowMatch.getNetworkTOS.equals(
                    ip4.getDiffServ)
                case _ => ip4.getPayload match {
                    case udp: UDP => checkField(field, udp)
                    case tcp: TCP => checkField(field, tcp)
                    case icmp: ICMP => checkField(field, icmp)
                    case _ => false
                }
            }

        private def checkField(field: Field, eth: Ethernet): Boolean = {
            log.info("checking field {}", field)
            field match {
                case Field.EthSrc => flowMatch.getEthSrc.equals(
                    eth.getSourceMACAddress)
                case Field.EthDst => flowMatch.getEthDst.equals(
                    eth.getDestinationMACAddress)
                case Field.EtherType => flowMatch.getEtherType.equals(
                    eth.getEtherType)
                case Field.VlanId => flowMatch.getVlanIds.containsAll(
                    eth.getVlanIDs)
                case _ => eth.getPayload match {
                    case ip4: IPv4 => checkField(field, ip4)
                    case _ => false
                }
            }
        }

        def matches(eth: Ethernet, i: Int = 0): Boolean =
            if (i < TraceKey.fields.length) {
                (if (flowMatch.isUsed(TraceKey.fields(i))) {
                    checkField(TraceKey.fields(i), eth)
                } else {
                    true
                }) && matches(eth, i+1)
            } else {
                true
            }
    }

    class TraceContext {
        var flowTraceId: UUID = null
        val requests: Set[UUID] = new HashSet[UUID]

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

    var traceTx: FlowStateTransaction[TraceKey, TraceContext] = _
    val traceContext: TraceContext = new TraceContext

    abstract override def clear(): Unit = {
        super.clear()
        traceContext.clear()
    }

    def tracingContext : String = {
        traceContext.toString
    }

    def tracingEnabled: Boolean = traceContext.enabled

    def tracingEnabled(traceRequestId: UUID): Boolean = {
        traceContext.enabled && traceContext.containsRequest(traceRequestId)
    }

    def enableTracing(traceRequestId: UUID): Unit = {
        if (!traceContext.enabled) {
            val key = new TraceKey(FlowMatches.fromEthernetPacket(ethernet))
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

    private def cloneTunnelKey(tunnelKey: FlowKeyTunnel): FlowKeyTunnel = {
        val newKey = new FlowKeyTunnel(tunnelKey.tun_id,
                                       tunnelKey.ipv4_src,
                                       tunnelKey.ipv4_dst,
                                       tunnelKey.ipv4_tos,
                                       tunnelKey.ipv4_ttl)
        newKey.tun_flags = tunnelKey.tun_flags
        newKey
    }

    private def interestingKey(k: FlowKey): Boolean = {
        !(k.isInstanceOf[FlowKeyTunnel] || k.isInstanceOf[FlowKeyInPort])
    }

    // setkey actions will modify the packet before it hits the egress
    // host. Add a tracekey that will match this packet
    def addModifiedTraceKeys(): Unit = {
        if (tracingEnabled) {
            var i = 0
            var newMatch: FlowMatch = null
            while (i < flowActions.size) {
                flowActions.get(i) match {
                    case set: FlowActionSetKey
                            if interestingKey(set.getFlowKey) => {
                        if (newMatch == null) {
                            newMatch = FlowMatches.fromEthernetPacket(ethernet)
                        }
                        newMatch.addKey(set.getFlowKey)
                    }
                    case _ => {}
                }
                i += 1
            }
            if (newMatch != null) {
                traceTx.putAndRef(new TraceKey(newMatch), traceContext)
            }
        }
    }

    def setTraceTunnelBit(actions: List[FlowAction]): List[FlowAction] = {
        var copied = false
        var i = 0
        var actions2 = actions
        while (i < actions2.size) {
            actions2.get(i) match {
                case setKey: FlowActionSetKey => {
                    setKey.getFlowKey match {
                        case t: FlowKeyTunnel => {
                            if (!copied) {
                                actions2 = new ArrayList(actions)
                                copied = true
                            }
                            val newTunnelKey = cloneTunnelKey(t)
                            newTunnelKey.tun_id =
                                newTunnelKey.tun_id | TraceTunnelKeyMask
                            actions2.set(i, FlowActions.setKey(newTunnelKey))
                        }
                        case _ => {}
                    }
                }
                case _ => {}
            }
            i += 1
        }
        actions2
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
        val key = new TraceKey(FlowMatches.fromEthernetPacket(ethernet))
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
