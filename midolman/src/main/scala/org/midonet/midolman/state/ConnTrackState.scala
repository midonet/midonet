/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.util.UUID

import akka.actor.ActorSystem

import org.midonet.cluster.client.Port
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.packets.{ICMP, UDP, TCP, IPAddr}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.flows.FlowTagger.FlowStateTag
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.functors.Callback0

object ConnTrackState {
    type ConnTrackValue = java.lang.Boolean

    val FORWARD_FLOW = java.lang.Boolean.TRUE
    val RETURN_FLOW = java.lang.Boolean.FALSE

    object ConnTrackKey {
        def apply(wcMatch: WildcardMatch, deviceId: UUID): ConnTrackKey =
            ConnTrackKey(wcMatch.getNetworkSourceIP,
                icmpIdOr(wcMatch, wcMatch.getTransportSource),
                wcMatch.getNetworkDestinationIP,
                icmpIdOr(wcMatch, wcMatch.getTransportDestination),
                wcMatch.getNetworkProtocol.byteValue(),
                deviceId)
    }

    case class ConnTrackKey(var networkSrc: IPAddr = null,
                            var icmpIdOrTransportSrc: Int = 0,
                            var networkDst: IPAddr = null,
                            var icmpIdOrTransportDst: Int = 0,
                            var networkProtocol: Byte = 0,
                            var deviceId: UUID = null) extends FlowStateTag {
        override def toString = s"conntrack:$networkSrc:$icmpIdOrTransportSrc:" +
                                s"$networkDst:$icmpIdOrTransportDst:" +
                                s"$networkProtocol:$deviceId"

        def invertInPlace(egressDeviceId: UUID): Unit = {
            val src = networkSrc
            networkSrc = networkDst
            networkDst = src

            val transportSrc = icmpIdOrTransportSrc
            icmpIdOrTransportSrc = icmpIdOrTransportDst
            icmpIdOrTransportDst = transportSrc

            deviceId = egressDeviceId
        }
    }

    def supportsConnectionTracking(wcMatch: WildcardMatch) = {
        val proto = wcMatch.getNetworkProtocol.byteValue
        TCP.PROTOCOL_NUMBER == proto || UDP.PROTOCOL_NUMBER == proto ||
        ICMP.PROTOCOL_NUMBER == proto
    }

    private def icmpIdOr(wcMatch: WildcardMatch, or: Int): Int = {
        if (ICMP.PROTOCOL_NUMBER == wcMatch.getNetworkProtocol.byteValue()) {
            val icmpId: java.lang.Short = wcMatch.getIcmpIdentifier
            if (icmpId ne null)
                return icmpId.intValue()
        }
        or
    }
}
