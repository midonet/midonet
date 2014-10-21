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

package org.midonet.midolman.state

import java.util.UUID

import akka.actor.ActorSystem

import org.midonet.cluster.client.Port
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.packets.{IPv4, ICMP, UDP, TCP, IPAddr}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.midolman.state.FlowState.FlowStateKey

object ConnTrackState {
    type ConnTrackValue = java.lang.Boolean

    val FORWARD_FLOW: ConnTrackValue = java.lang.Boolean.TRUE
    val RETURN_FLOW: ConnTrackValue = java.lang.Boolean.FALSE

    object ConnTrackKey {
        def apply(wcMatch: WildcardMatch, deviceId: UUID): ConnTrackKey =
            ConnTrackKey(wcMatch.getNetworkSrcIP,
                         icmpIdOr(wcMatch, wcMatch.getSrcPort),
                         wcMatch.getNetworkDstIP,
                         icmpIdOr(wcMatch, wcMatch.getDstPort),
                         wcMatch.getNetworkProto.byteValue(),
                         deviceId)
    }

    case class ConnTrackKey(networkSrc: IPAddr,
                            icmpIdOrTransportSrc: Int,
                            networkDst: IPAddr,
                            icmpIdOrTransportDst: Int,
                            networkProtocol: Byte,
                            deviceId: UUID) extends FlowStateKey {
        override def toString = s"conntrack:$networkSrc:$icmpIdOrTransportSrc:" +
                                s"$networkDst:$icmpIdOrTransportDst:" +
                                s"$networkProtocol:$deviceId"
    }

    def EgressConnTrackKey(wcMatch: WildcardMatch, egressDeviceId: UUID): ConnTrackKey =
        ConnTrackKey(wcMatch.getNetworkDstIP,
                     icmpIdOr(wcMatch, wcMatch.getDstPort),
                     wcMatch.getNetworkSrcIP,
                     icmpIdOr(wcMatch, wcMatch.getSrcPort),
                     wcMatch.getNetworkProto.byteValue(),
                     egressDeviceId)

    def supportsConnectionTracking(wcMatch: WildcardMatch): Boolean = {
        val proto = wcMatch.getNetworkProto
        IPv4.ETHERTYPE == wcMatch.getEtherType &&
                (TCP.PROTOCOL_NUMBER == proto ||
                 UDP.PROTOCOL_NUMBER == proto ||
                 ICMP.PROTOCOL_NUMBER == proto)
    }

    private def icmpIdOr(wcMatch: WildcardMatch, or: Integer): Int = {
        if (ICMP.PROTOCOL_NUMBER == wcMatch.getNetworkProto) {
            val icmpId: java.lang.Short = wcMatch.getIcmpIdentifier
            if (icmpId ne null)
                return icmpId.intValue()
        }
        or.intValue()
    }
}

/**
 * Connection tracking state. A ConnTrackKey is generated using the forward
 * flow's egress device ID (a device is used instead of a port in order to
 * support underlay asymmetric routing as well as bridge flooding). For return
 * packets, the ingress device is used to lookup the connection tracking key
 * that would have been written by the forward packet. The forward flow takes
 * into account the original wildcard match, that is, the state of the packet
 * when it ingressed. The return flow key, however, is built using the modified
 * wildcard match, which, the source fields swapped with the destination fields,
 * translates to the original wildcard match when the return flow ingresses.
 */
trait ConnTrackState extends FlowState {
    import ConnTrackState._

    var conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = _
    // TODO: make these fields private
    var isConnectionTracked: Boolean = false
    var flowDirection: ConnTrackValue = _
    private var connKey: ConnTrackKey = _

    override def clear(): Unit = {
        connKey = null
        isConnectionTracked = false
    }

    def isForwardFlow: Boolean =
        if (isConnectionTracked) {
            flowDirection ne RETURN_FLOW
        } else if (supportsConnectionTracking(pktCtx.wcmatch)) {
            if (pktCtx.isGenerated) { // We treat generated packets as return flows
                false
            } else {
                isConnectionTracked = true
                connKey = ConnTrackKey(pktCtx.origMatch, fetchIngressDevice())
                pktCtx.addFlowTag(connKey)
                flowDirection = conntrackTx.get(connKey)
                val res = flowDirection ne RETURN_FLOW
                log.debug("Connection is forward flow = {}",
                          res.asInstanceOf[java.lang.Boolean])
                res
            }
        } else {
            true
        }

    def trackConnection(egressDeviceId: UUID): Unit =
        if (isConnectionTracked && (flowDirection ne RETURN_FLOW)) {
            val returnKey = EgressConnTrackKey(pktCtx.wcmatch, egressDeviceId)
            pktCtx.addFlowTag(returnKey)
            if (flowDirection eq null) { // A new forward flow
                conntrackTx.putAndRef(connKey, FORWARD_FLOW)
                conntrackTx.putAndRef(returnKey, RETURN_FLOW)
            } else if (flowDirection eq FORWARD_FLOW) {
                // We don't ref count the return flow key, which is a weak
                // reference
                conntrackTx.ref(connKey)
                conntrackTx.ref(returnKey)
            }
        }

    protected def fetchIngressDevice(): UUID = {
        implicit val actorSystem: ActorSystem = null
        try {
            VirtualTopologyActor.tryAsk[Port](pktCtx.inputPort).deviceID
        } catch {
            case ignored: NullPointerException =>
                throw org.midonet.midolman.simulation.FixPortSets
        }
    }
}
