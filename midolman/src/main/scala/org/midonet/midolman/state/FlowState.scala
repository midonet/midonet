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

import org.midonet.cluster.flowstate.proto.FlowState.{Conntrack, Nat, Trace}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.TraceKey
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPv4Addr, FlowStatePackets}
import org.midonet.sdn.flows.FlowTagger.FlowStateTag
import org.midonet.util.Clearable

object FlowStateAgentPackets
    extends FlowStatePackets[ConnTrackKey, NatKey, TraceKey] {

    override def connTrackKeyFromSbe(conntrack: Conntrack): ConnTrackKey = {
        ConnTrackKey(ipFromSbe(conntrack.srcIpType, conntrack.srcIp),
                     conntrack.srcPort,
                     ipFromSbe(conntrack.dstIpType, conntrack.dstIp),
                     conntrack.dstPort,
                     conntrack.protocol.toByte,
                     uuidFromSbe(conntrack.device))
    }

    override def natKeyFromSbe(nat: Nat): NatKey = {
        NatKey(natKeyTypeFromSbe(nat.keyType),
               ipFromSbe(nat.keySrcIpType, nat.keySrcIp).asInstanceOf[IPv4Addr],
               nat.keySrcPort,
               ipFromSbe(nat.keyDstIpType, nat.keyDstIp).asInstanceOf[IPv4Addr],
               nat.keyDstPort, nat.keyProtocol.toByte,
               uuidFromSbe(nat.keyDevice))
    }

    override def traceFromSbe(trace: Trace): TraceKey = {
        TraceKey(macFromSbe(trace.srcMac),
                 macFromSbe(trace.dstMac),
                 trace.etherType.toShort,
                 ipFromSbe(trace.srcIpType, trace.srcIp),
                 ipFromSbe(trace.dstIpType, trace.dstIp),
                 trace.protocol.toByte,
                 trace.srcPort, trace.dstPort)
    }
}

object FlowState {

    trait FlowStateKey extends FlowStateTag

    @inline
    def isStateMessage(fmatch: FlowMatch): Boolean = {
        fmatch.getTunnelKey == FlowStateAgentPackets.TUNNEL_KEY
    }
}

/**
 * Base trait for flow state management during a simulation. Implementers of
 * this trait must ensure it is stackable with other state traits.
 */
trait FlowState extends Clearable { this: PacketContext => }
