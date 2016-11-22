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

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.odp.FlowMatch
import org.midonet.packets.ConnTrackState.{ConnTrackKeyAllocator, ConnTrackKeyStore}
import org.midonet.packets.{ICMP, IPAddr, IPv4, TCP, UDP}
import org.midonet.sdn.state.FlowStateTransaction

object ConnTrackState {
    type ConnTrackValue = java.lang.Boolean

    val RETURN_FLOW: ConnTrackValue = java.lang.Boolean.FALSE

    object ConnTrackKey extends ConnTrackKeyAllocator[ConnTrackKey] {
        def apply(wcMatch: FlowMatch, deviceId: UUID): ConnTrackKey =
            ConnTrackKey(wcMatch.getNetworkSrcIP,
                         icmpIdOr(wcMatch, wcMatch.getSrcPort),
                         wcMatch.getNetworkDstIP,
                         icmpIdOr(wcMatch, wcMatch.getDstPort),
                         wcMatch.getNetworkProto.byteValue(),
                         deviceId)

        def apply(networkSrc: IPAddr,
                  icmpIdOrTransportSrc: Int,
                  networkDst: IPAddr,
                  icmpIdOrTransportDst: Int,
                  networkProtocol: Byte,
                  deviceId: UUID): ConnTrackKey =
            new ConnTrackKeyStore(networkSrc, icmpIdOrTransportSrc,
                                  networkDst, icmpIdOrTransportDst,
                                  networkProtocol, deviceId) with FlowStateKey
    }

    type ConnTrackKey = ConnTrackKeyStore with FlowStateKey

    def EgressConnTrackKey(wcMatch: FlowMatch, egressDeviceId: UUID): ConnTrackKey =
        ConnTrackKey(wcMatch.getNetworkDstIP,
                     icmpIdOr(wcMatch, wcMatch.getDstPort),
                     wcMatch.getNetworkSrcIP,
                     icmpIdOr(wcMatch, wcMatch.getSrcPort),
                     wcMatch.getNetworkProto.byteValue(),
                     egressDeviceId)

    def supportsConnectionTracking(wcMatch: FlowMatch): Boolean = {
        val proto = wcMatch.getNetworkProto
        IPv4.ETHERTYPE == wcMatch.getEtherType &&
                (TCP.PROTOCOL_NUMBER == proto ||
                 UDP.PROTOCOL_NUMBER == proto ||
                 ICMP.PROTOCOL_NUMBER == proto)
    }

    private def icmpIdOr(wcMatch: FlowMatch, or: Integer): Int = {
        if (ICMP.PROTOCOL_NUMBER == wcMatch.getNetworkProto) {
            return wcMatch.getIcmpIdentifier
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
 * into account the original flow match, that is, the state of the packet
 * when it ingressed. The return flow key, however, is built using the modified
 * flow match, which, the source fields swapped with the destination fields,
 * translates to the original flow match when the return flow ingresses.
 */
trait ConnTrackState extends FlowState { this: PacketContext =>
    import ConnTrackState._

    var conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = _

    private var isConnectionTracked: Boolean = false
    private var flowDirection: ConnTrackValue = _
    private var connKey: ConnTrackKey = _

    abstract override def clear(): Unit = {
        super.clear()
        if (conntrackTx ne null) conntrackTx.flush()
        connKey = null
        isConnectionTracked = false
    }

    def resetConnTrackState(): Unit = {
        this.conntrackTx = null
        this.isConnectionTracked = false
        this.flowDirection = null
        this.connKey = null
    }

    /*
     * NOTE: Flow tagging and conntrack.
     *
     * Conntrack key querying can happen in different circumstances:
     *
     *   1.- A genuine, regular, forward flow. We query the conntrack table
     *       and find nothing. We generate a conntrack key based on the egress
     *       device (see trackConnection(), below) and install it on the table.
     *
     *   2.- A bogus forward flow. The flow is actually a return flow, but the
     *       conntrack key has, for some reason, not arrived to this host yet.
     *       We want to invalidate the resulting flow upon later arrival of
     *       the key.
     *
     *   3.- A return flow. We query the conntrack table and find the conntrack
     *       key for this flow.
     *
     * Case #2 needs tag-based flow invalidation. Cases #1 and #3 do not need it,
     * There is no event related to the conntrack key (including expiration, because
     * a longer lifetime is actually desirable) that would render the flow invalid.
     *
     * Therefore, isForwardFlow must tag only if its result is true, based on
     * the possibility that the result is bogus and the conntrack key is due
     * to arrive at a later time.
     */
    def isForwardFlow: Boolean =
        if (isConnectionTracked) {
            flowDirection ne RETURN_FLOW
        } else if (supportsConnectionTracking(wcmatch)) {
            if (isGenerated) { // We treat generated packets as return flows
                false
            } else {
                isConnectionTracked = true
                connKey = ConnTrackKey(origMatch, fetchIngressDevice())
                flowDirection = conntrackTx.get(connKey)
                if (flowDirection ne RETURN_FLOW)
                    addFlowTag(connKey)
                val res = flowDirection ne RETURN_FLOW
                log.debug("Connection is forward flow = {}",
                          res.asInstanceOf[java.lang.Boolean])
                res
            }
        } else {
            true
        }

    def trackConnection(egressDeviceId: UUID): Unit = {
        if (isConnectionTracked && (flowDirection ne RETURN_FLOW)) {
            val keyMatch = if (isEncapped) recircMatch else wcmatch
            val returnKey = EgressConnTrackKey(keyMatch, egressDeviceId)
            conntrackTx.putAndRef(returnKey, RETURN_FLOW)
        }
    }

    protected def fetchIngressDevice(): UUID = {
        implicit val actorSystem: ActorSystem = null
        VirtualTopology.tryGet(classOf[Port], inputPort).deviceId
    }
}
