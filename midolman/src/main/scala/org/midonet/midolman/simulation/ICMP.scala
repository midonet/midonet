/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.midolman.simulation

import org.midonet.odp.FlowMatch
import org.midonet.packets.ICMP.{EXCEEDED_CODE, UNREACH_CODE}
import org.midonet.packets._

object Icmp {

    trait IcmpErrorSender[IP <: IPAddr] {
        /**
         * Send an ICMP error message.
         */
        protected def icmpAnswer(inPort: RouterPort,
                                 context: PacketContext,
                                 fmatch: FlowMatch,
                                 icmpType: Byte,
                                 icmpCode: Any): Option[Ethernet]

        /**
         * Will be called whenever an ICMP unreachable is needed for the given
         * IP version.
         */
        def unreachableProhibitedIcmp(inPort: RouterPort, context: PacketContext,
                                      fmatch: FlowMatch) =
            icmpAnswer(inPort, context, fmatch, ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)

        /**
         * Will be called whenever an ICMP Unreachable network is needed for the
         * given IP version.
         */
        def unreachableNetIcmp(inPort: RouterPort, context: PacketContext, fmatch: FlowMatch) =
            icmpAnswer(inPort, context, fmatch, ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_NET)

        /**
         * Will be called whenever an ICMP Unreachable host is needed for the
         * given IP version.
         */
        def unreachableHostIcmp(inPort: RouterPort, context: PacketContext, fmatch: FlowMatch) =
            icmpAnswer(inPort, context, fmatch, ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_HOST)

        def unreachableFragNeededIcmp(inPort: RouterPort, context: PacketContext, fmatch: FlowMatch) =
            icmpAnswer(inPort, context, fmatch, ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FRAG_NEEDED)

        /**
         * Will be called whenever an ICMP Time Exceeded is needed for the given
         * IP version.
         */
        def timeExceededIcmp(inPort: RouterPort, context: PacketContext, fmatch: FlowMatch) =
            icmpAnswer(inPort, context, fmatch, ICMP.TYPE_TIME_EXCEEDED, EXCEEDED_CODE.EXCEEDED_TTL)
    }

    implicit object IPv4Icmp extends IcmpErrorSender[IPv4Addr] {
        val invalidAddr = IPv4Addr(0xffffffff)

        override def icmpAnswer(
                inPort: RouterPort,
                context: PacketContext,
                fmatch: FlowMatch,
                icmpType: Byte,
                icmpCode: Any): Option[Ethernet] = {
            context.log.debug("Prepare an ICMP response")
            val packet = context.ethernet

            // Check whether the original packet is allowed to trigger ICMP.
            if (inPort == null) {
                context.log.debug("Cannot send ICMP because input port is null")
                return None
            }
            if (inPort.portAddress4 eq null) {
                context.log.debug("Port {} does not have an IPv4 address",
                                  inPort.id)
                return None
            }
            if (!canSend(fmatch, inPort, context)) {
                context.log.debug("ICMP not allowed for this packet")
                return None
            }
            // Build the ICMP packet from inside-out: ICMP, IPv4, Ethernet headers.
            context.log.debug(s"Generating ICMP error $icmpType:$icmpCode")
            val icmp = buildError(icmpType, icmpCode, fmatch, packet)

            val ip = new IPv4()
            ip.setPayload(icmp)
            ip.setProtocol(ICMP.PROTOCOL_NUMBER)
            // The nwDst is the source of triggering IPv4 as seen by this router.
            ip.setDestinationAddress(fmatch.getNetworkSrcIP.asInstanceOf[IPv4Addr])
            // The nwSrc is the address of the ingress port.
            ip.setSourceAddress(inPort.portAddress4.getAddress)
            val eth = new Ethernet()
            eth.setPayload(ip)
            eth.setEtherType(IPv4.ETHERTYPE)
            eth.setSourceMACAddress(inPort.portMac)
            eth.setDestinationMACAddress(fmatch.getEthSrc)

            Some(eth)
        }

        private def buildError(icmpType: Byte, icmpCode: Any,
                               forMatch: FlowMatch, forPacket: Ethernet)
                : ICMP = {
            forMatch.applyTo(forPacket)
            val ipPkt = forPacket.getPayload.asInstanceOf[IPv4]
            icmpCode match {
                case c: ICMP.EXCEEDED_CODE if icmpType == ICMP.TYPE_TIME_EXCEEDED =>
                    val icmp = new ICMP()
                    icmp.setTimeExceeded(c, ipPkt)
                    icmp
                case c: ICMP.UNREACH_CODE if icmpType == ICMP.TYPE_UNREACH =>
                    val icmp = new ICMP()
                    icmp.setUnreachable(c, ipPkt)
                    icmp
                case _ =>
                    null
            }
        }

        /**
         * Determine whether a packet can trigger an ICMP error.  Per RFC 1812 sec.
         * 4.3.2.7, some packets should not trigger ICMP errors:
         *   1) Other ICMP errors.
         *   2) Invalid IP packets.
         *   3) Destined to IP bcast or mcast address.
         *   4) Destined to a link-layer bcast or mcast.
         *   5) With source network prefix zero or invalid source.
         *   6) Second and later IP fragments.
         *
         * @return True if-and-only-if the packet meets none of the above conditions
         *         - i.e. it can trigger an ICMP error message.
         */
        private def canSend(fmatch: FlowMatch, outPort: RouterPort,
                            context: PacketContext): Boolean = {
            if (fmatch.getEtherType != IPv4.ETHERTYPE)
                return false

            if (fmatch.getNetworkProto == ICMP.PROTOCOL_NUMBER &&
                ICMP.isError(fmatch.getSrcPort.toByte)) {
                    context.log.debug("Skipping generation of ICMP error for ICMP error packet")
                    return false
            }

            // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
            // Ignore packets to IP mcast addresses.
            if (fmatch.getNetworkDstIP.isMcast) {
                context.log.debug("Not generating ICMP Unreachable for packet "+
                                  "to an IP multicast address.")
                return false
            }
            // Ignore packets sent to the local-subnet IP broadcast address of the
            // intended egress port.
            if (null != outPort && (outPort.portAddress4 ne null) &&
                fmatch.getNetworkDstIP == outPort.portAddress4.toBroadcastAddress) {
                context.log.debug("Not generating ICMP Unreachable for packet to "
                                + "the subnet local broadcast address.")
                return false
            }
            // Ignore packets to Ethernet broadcast and multicast addresses.
            if (fmatch.getEthDst.mcast) {
                context.log.debug("Not generating ICMP Unreachable for packet to "
                                + "Ethernet broadcast or multicast address.")
                return false
            }
            // Ignore packets with source network prefix zero or invalid source.
            // TODO(pino): See RFC 1812 sec. 5.3.7
            if (fmatch.getNetworkSrcIP == invalidAddr ||
                fmatch.getNetworkDstIP == invalidAddr) {
                context.log.debug(
                    "Not generating ICMP Unreachable for all-hosts broadcast packet")
                return false
            }
            // TODO(pino): check this fragment offset
            // Ignore datagram fragments other than the first one.
            if (fmatch.getIpFragmentType == IPFragmentType.Later) {
                context.log.debug(
                    "Not generating ICMP Unreachable for IP fragment packet")
                return false
            }

            true
        }
    }
}
