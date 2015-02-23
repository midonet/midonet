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

import java.util.{ArrayList, Collections, List, UUID}

import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.odp.flows.{FlowAction, FlowActionSetKey}
import org.midonet.odp.flows.{FlowKey, FlowKeyEthernet, FlowKeyEtherType}
import org.midonet.odp.flows.{FlowKeyIPv4, FlowKeyIPv6, FlowKeyTCP, FlowKeyUDP}
import org.midonet.packets.{Ethernet, MAC,  IPv4, IPv6}
import org.midonet.packets.{IPv4Addr, IPv6Addr, IPAddr}
import org.midonet.packets.{TCP, UDP, IPacket}
import org.midonet.sdn.state.FlowStateTransaction

object TraceState {
    val log = LoggerFactory.getLogger(classOf[TraceState])

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

    case class TraceContext(flowTraceId: UUID = UUID.randomUUID) {
        val requests: List[UUID] = new ArrayList[UUID](1)

        def addRequest(request: UUID): Boolean = requests.add(request)
        def containsRequest(request: UUID): Boolean = requests.contains(request)
    }
}

trait TraceState extends FlowState { this: PacketContext =>
    import org.midonet.midolman.state.TraceState._

    var tracing: List[UUID] = new ArrayList[UUID]
    var traceTx: FlowStateTransaction[TraceKey, TraceContext] = _
    private var clearEnabled = true

    def prepareForSimulationWithTracing(): Unit = {
        // clear non-trace info
        clearEnabled = false
        clear()
        clearEnabled = true

        runFlowRemovedCallbacks()
    }

    override def clear(): Unit = {
        super.clear()

        if (clearEnabled) {
            tracing.clear()
            traceTx.flush()
        }
    }
}
