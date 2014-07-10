/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.nio.ByteBuffer
import java.util.Random

import org.midonet.midolman.rules.NatTarget
import org.midonet.packets.{IPv4Addr, IPv4, ICMP, TCP, UDP, IPAddr}
import org.midonet.sdn.flows.FlowTagger.FlowStateTag
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.functors.Callback0

object NatState {
    private val WILDCARD_PORT = 0

    object NatKey {
        final val USHORT = 0xffff // Constant used to prevent sign extension

        sealed abstract class Type
        case object FWD_SNAT extends Type
        case object FWD_DNAT extends Type
        case object FWD_STICKY_DNAT extends Type
        case object REV_SNAT extends Type
        case object REV_DNAT extends Type
        case object REV_STICKY_DNAT extends Type

        def apply(wcMatch: WildcardMatch, keyType: Type): NatKey = {
            val key = NatKey(keyType,
                             wcMatch.getNetworkSourceIP,
                             wcMatch.getTransportSource,
                             wcMatch.getNetworkDestinationIP,
                             wcMatch.getTransportDestination,
                             wcMatch.getNetworkProtocol.byteValue())

            if (wcMatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER)
                processIcmp(key, wcMatch)

            key
        }

        private def processIcmp(natKey: NatKey, wcMatch: WildcardMatch): Unit =
            wcMatch.getTransportSource.byteValue() match {
                case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                    val port = wcMatch.getIcmpIdentifier.intValue() & USHORT
                    natKey.transportSrc = port
                    natKey.transportDst = port
                case ICMP.TYPE_PARAMETER_PROBLEM | ICMP.TYPE_UNREACH |
                     ICMP.TYPE_TIME_EXCEEDED =>
                    // The nat mapping lookup should be done based on the
                    // contents of the ICMP data field.
                    val bb = ByteBuffer.wrap(wcMatch.getIcmpData)
                    val ipv4 = new IPv4
                    ipv4.deserializeHeader(bb)
                    val proto = ipv4.getProtocol
                    if (proto == ICMP.PROTOCOL_NUMBER) {
                        // If replying to a prev. ICMP, mapping was done
                        // against the icmp id.
                        val icmp = new ICMP
                        icmp.deserialize(bb)
                        val port = icmp.getIdentifier
                        natKey.transportSrc = port
                        natKey.transportDst = port
                    } else {
                        // Invert src and dst because the icmpData contains the
                        // original msg that the ICMP ERROR replies to.
                        // TCP/UDP deserialize would likely fail since ICMP data
                        // doesn't contain the full datagram.
                        val packet = bb.slice
                        natKey.transportSrc = TCP.getSourcePort(packet)
                        natKey.transportDst = TCP.getDestinationPort(packet)
                    }
                    natKey.networkProtocol = proto
                case _ =>
                    natKey.transportSrc = 0
                    natKey.transportDst = 0
            }
        }

    case class NatKey(var keyType: NatKey.Type,
                      var networkSrc: IPAddr = null,
                      var transportSrc: Int = 0,
                      var networkDst: IPAddr = null,
                      var transportDst: Int = 0,
                      var networkProtocol: Byte = 0) extends FlowStateTag {
        override def toString = s"nat:$keyType:$networkSrc:$transportSrc:" +
                                s"$networkDst:$transportDst:$networkProtocol"
    }

    case class NatBinding(var networkAddress: IPAddr,
                          var transportPort: Int)
}
