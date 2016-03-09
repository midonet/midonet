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

import org.midonet.cluster.flowstate.proto.{FlowState => FlowStateSbe, _}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState._
import org.midonet.midolman.state.TraceState.TraceKey
import org.midonet.odp.FlowMatch
import org.midonet.packets.FlowStateSbeEncoder._
import org.midonet.packets.IPv4Addr

object FlowStatePackets {

    def isStateMessage(fmatch: FlowMatch): Boolean =
        fmatch.getTunnelKey == TUNNEL_KEY

    def natKeyTypeFromSbe(t: NatKeyType): KeyType = t match {
        case NatKeyType.FWD_SNAT => NatState.FWD_SNAT
        case NatKeyType.FWD_DNAT => NatState.FWD_DNAT
        case NatKeyType.FWD_STICKY_DNAT => NatState.FWD_STICKY_DNAT
        case NatKeyType.REV_SNAT => NatState.REV_SNAT
        case NatKeyType.REV_DNAT => NatState.REV_DNAT
        case NatKeyType.REV_STICKY_DNAT => NatState.REV_STICKY_DNAT
        case _ => null
    }

    def natKeySbeType(t: KeyType): NatKeyType = t match {
        case NatState.FWD_SNAT => NatKeyType.FWD_SNAT
        case NatState.FWD_DNAT => NatKeyType.FWD_DNAT
        case NatState.FWD_STICKY_DNAT => NatKeyType.FWD_STICKY_DNAT
        case NatState.REV_SNAT => NatKeyType.REV_SNAT
        case NatState.REV_DNAT => NatKeyType.REV_DNAT
        case NatState.REV_STICKY_DNAT => NatKeyType.REV_STICKY_DNAT
    }

    def connTrackKeyFromSbe(conntrack: FlowStateSbe.Conntrack): ConnTrackKey = {
        ConnTrackKey(ipFromSbe(conntrack.srcIpType, conntrack.srcIp),
                     conntrack.srcPort,
                     ipFromSbe(conntrack.dstIpType, conntrack.dstIp),
                     conntrack.dstPort,
                     conntrack.protocol.toByte,
                     uuidFromSbe(conntrack.device))
    }

    def connTrackKeyToSbe(key: ConnTrackKey,
                          conntrack: FlowStateSbe.Conntrack): Unit = {
        uuidToSbe(key.deviceId, conntrack.device)
        ipToSbe(key.networkSrc, conntrack.srcIp)
        ipToSbe(key.networkDst, conntrack.dstIp)
        conntrack.srcPort(key.icmpIdOrTransportSrc)
        conntrack.dstPort(key.icmpIdOrTransportDst)
        conntrack.protocol(key.networkProtocol)
        conntrack.srcIpType(ipSbeType(key.networkSrc))
        conntrack.dstIpType(ipSbeType(key.networkDst))
    }

    def natKeyFromSbe(nat: FlowStateSbe.Nat): NatKey =
        NatKey(natKeyTypeFromSbe(nat.keyType),
               ipFromSbe(nat.keySrcIpType, nat.keySrcIp).asInstanceOf[IPv4Addr],
               nat.keySrcPort,
               ipFromSbe(nat.keyDstIpType, nat.keyDstIp).asInstanceOf[IPv4Addr],
               nat.keyDstPort, nat.keyProtocol.toByte,
               uuidFromSbe(nat.keyDevice))

    def natBindingFromSbe(nat: FlowStateSbe.Nat): NatBinding =
        NatBinding(
            ipFromSbe(nat.valueIpType, nat.valueIp).asInstanceOf[IPv4Addr],
            nat.valuePort)

    def natToSbe(key: NatKey, value: NatBinding,
                 nat: FlowStateSbe.Nat): Unit = {
        uuidToSbe(key.deviceId, nat.keyDevice)
        ipToSbe(key.networkSrc, nat.keySrcIp)
        ipToSbe(key.networkDst, nat.keyDstIp)
        ipToSbe(value.networkAddress, nat.valueIp)
        nat.keySrcPort(key.transportSrc)
        nat.keyDstPort(key.transportDst)
        nat.valuePort(value.transportPort)
        nat.keyProtocol(key.networkProtocol)
        nat.keySrcIpType(ipSbeType(key.networkSrc))
        nat.keyDstIpType(ipSbeType(key.networkDst))
        nat.valueIpType(ipSbeType(value.networkAddress))
        nat.keyType(natKeySbeType(key.keyType))
    }

    def traceFromSbe(trace: FlowStateSbe.Trace): TraceKey =
        TraceKey(macFromSbe(trace.srcMac),
                 macFromSbe(trace.dstMac),
                 trace.etherType.toShort,
                 ipFromSbe(trace.srcIpType, trace.srcIp),
                 ipFromSbe(trace.dstIpType, trace.dstIp),
                 trace.protocol.toByte,
                 trace.srcPort, trace.dstPort)

    def traceToSbe(flowTraceId: UUID, key: TraceKey,
                   trace: FlowStateSbe.Trace): Unit = {
        uuidToSbe(flowTraceId, trace.flowTraceId)
        ipToSbe(key.networkSrc, trace.srcIp)
        ipToSbe(key.networkDst, trace.dstIp)
        macToSbe(key.ethSrc, trace.srcMac)
        macToSbe(key.ethDst, trace.dstMac)
        trace.srcPort(key.srcPort)
        trace.dstPort(key.dstPort)
        trace.etherType(key.etherType)
        trace.protocol(key.networkProto)
        trace.srcIpType(ipSbeType(key.networkSrc))
        trace.dstIpType(ipSbeType(key.networkDst))
    }
}
