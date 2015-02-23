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
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowActionSetKey}
import org.midonet.odp.flows.{FlowKey, FlowKeyEthernet, FlowKeyEtherType}
import org.midonet.odp.flows.{FlowKeyIPv4, FlowKeyIPv6, FlowKeyTCP, FlowKeyUDP}
import org.midonet.odp.flows.{FlowKeyTunnel}
import org.midonet.packets.{Ethernet, MAC,  IPv4, IPv6}
import org.midonet.packets.{IPv4Addr, IPv6Addr, IPAddr}
import org.midonet.packets.{TCP, UDP, IPacket}
import org.midonet.sdn.state.FlowStateTransaction

object TraceState {
    val log = LoggerFactory.getLogger(classOf[TraceState])

    val TraceTunnelKeyMask = 0x80000
    val TraceLoggingContextKey = "traceIds"

    def traceBitPresent(tunnelId: Long): Boolean = {
        (tunnelId & TraceTunnelKeyMask) != 0
    }

    object TraceKey {
        def fromEthernet(
            ethernet: Ethernet,
            actions: List[FlowAction] = Collections.emptyList()): TraceKey = {
            var ethSrc = ethernet.getSourceMACAddress
            var ethDst = ethernet.getDestinationMACAddress
            var etherType = ethernet.getEtherType
            var networkSrc: IPAddr = null
            var networkDst: IPAddr = null
            var networkProto: Byte = 0
            var networkTOS: Byte = 0
            var srcPort: Int = 0
            var dstPort: Int = 0


            val processPayload: (IPacket => Unit) =
                (payload: IPacket) => payload match {
                    case tcp: TCP => {
                        srcPort = tcp.getSourcePort
                        dstPort = tcp.getDestinationPort
                    }
                    case udp: UDP => {
                        srcPort = udp.getSourcePort
                        dstPort = udp.getDestinationPort
                    }
                    case _ => {}
                }

            ethernet.getPayload match {
                case ipv4: IPv4 => {
                    if (etherType != IPv4.ETHERTYPE) {
                        log.warn("Packet with ethertype {} has IPv4 payload")
                    }
                    networkSrc = ipv4.getSourceIPAddress
                    networkDst = ipv4.getDestinationIPAddress
                    networkProto = ipv4.getProtocol
                    networkTOS = ipv4.getDiffServ

                    processPayload(ipv4.getPayload)
                }
                case ipv6: IPv6 => {
                    if (etherType != IPv4.ETHERTYPE) {
                        log.warn("Packet with ethertype {} has IPv4 payload")
                    }
                    networkSrc = ipv6.getSourceAddress
                    networkDst = ipv6.getDestinationAddress
                    networkProto = ipv6.getNextHeader
                    networkTOS = ipv6.getTrafficClass

                    processPayload(ipv6.getPayload)
                }
                case _ => {}
            }

            val tryApplyKey = (key: FlowKey) => {
                key match {
                    case eth: FlowKeyEthernet => {
                        ethSrc = MAC.fromAddress(eth.eth_src)
                        ethDst = MAC.fromAddress(eth.eth_dst)
                    }
                    case ethtype: FlowKeyEtherType => {
                        etherType = ethtype.etherType
                    }
                    case ipv4: FlowKeyIPv4 => {
                        networkSrc = IPv4Addr.fromInt(ipv4.ipv4_src)
                        networkDst = IPv4Addr.fromInt(ipv4.ipv4_dst)
                        networkProto = ipv4.ipv4_proto
                        networkTOS = ipv4.ipv4_tos
                    }
                    case ipv6: FlowKeyIPv6 => {
                        val intSrc = ipv6.ipv6_src;
                        val intDst = ipv6.ipv6_dst;
                        networkSrc = new IPv6Addr(
                            (intSrc(0).toLong << 32) |
                                (intSrc(1) & 0xFFFFFFFFL),
                            (intSrc(2).toLong << 32) |
                                (intSrc(3) & 0xFFFFFFFFL));
                        networkDst = new IPv6Addr(
                            (intDst(0).toLong << 32) |
                                (intDst(1) & 0xFFFFFFFFL),
                            (intDst(2).toLong << 32) |
                                (intDst(3) & 0xFFFFFFFFL));
                        networkProto = ipv6.ipv6_proto
                        networkTOS = ipv6.ipv6_tclass
                    }
                    case tcp: FlowKeyTCP => {
                        srcPort = tcp.tcp_src
                        dstPort = tcp.tcp_dst
                        networkProto = TCP.PROTOCOL_NUMBER
                    }
                    case udp: FlowKeyUDP => {
                        srcPort = udp.udp_src
                        dstPort = udp.udp_src
                        networkProto = UDP.PROTOCOL_NUMBER
                    }
                    case _ => {}
                }
            }

            var i = actions.size
            while (i > 0) {
                i -= 1
                actions.get(i) match {
                    case k: FlowActionSetKey =>
                        tryApplyKey(k.getFlowKey)
                    case _ => { /* ignore */ }
                }
            }
            TraceKey(ethSrc, ethDst, etherType, networkSrc, networkDst,
                     networkProto, networkTOS, srcPort, dstPort)
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
            val key = TraceKey.fromEthernet(ethernet)
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

    private def interestingKey(k: FlowKey): Boolean =
        k.isInstanceOf[FlowKeyEthernet] || k.isInstanceOf[FlowKeyEtherType] ||
            k.isInstanceOf[FlowKeyIPv4] || k.isInstanceOf[FlowKeyIPv6] ||
            k.isInstanceOf[FlowKeyTCP] || k.isInstanceOf[FlowKeyUDP]

    // setkey actions will modify the packet before it hits the egress
    // host. Add a tracekey that will match this packet
    def addModifiedTraceKeys(): Unit = {
        if (tracingEnabled) {
            var i = 0
            var addModifiedKey = false
            while (i < flowActions.size) {
                flowActions.get(i) match {
                    case set: FlowActionSetKey
                            if interestingKey(set.getFlowKey) => {
                                addModifiedKey = true
                    }
                    case _ => {}
                }
                i += 1
            }
            if (addModifiedKey) {
                traceTx.putAndRef(TraceKey.fromEthernet(ethernet, flowActions),
                                  traceContext)
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
        val key = TraceKey.fromEthernet(ethernet)
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
