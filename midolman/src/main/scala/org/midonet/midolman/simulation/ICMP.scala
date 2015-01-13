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
package org.midonet.midolman.simulation

import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.odp.FlowMatch
import org.midonet.packets.ICMP.{EXCEEDED_CODE, UNREACH_CODE}
import org.midonet.packets._

object Icmp {

    trait IcmpErrorSender[IP <: IPAddr] {
        /**
         * Send an ICMP error message.
         *
         * @param ingressMatch
         *            The flow match that caused the message to be generated
         * @param packet
         *            The original packet that started the simulation
         */
        protected def icmpAnswer(inPort: RouterPort,
                                 ingressMatch: FlowMatch,
                                 packet: Ethernet,
                                 icmpType: Byte,
                                 icmpCode: Any)
                                (implicit context: PacketContext): Option[Ethernet]

        /**
         * Will be called whenever an ICMP unreachable is needed for the given
         * IP version.
         */
        def unreachableProhibitedIcmp(inPort: RouterPort,
                                      wMatch: FlowMatch,
                                      frame: Ethernet)
                                     (implicit originalPktContex: PacketContext) =
            icmpAnswer(inPort, wMatch, frame, ICMP.TYPE_UNREACH,
                 UNREACH_CODE.UNREACH_FILTER_PROHIB)

        /**
         * Will be called whenever an ICMP Unreachable network is needed for the
         * given IP version.
         */
        def unreachableNetIcmp(inPort: RouterPort,
                               wMatch: FlowMatch,
                               frame: Ethernet)
                              (implicit originalPktContex: PacketContext) =
            icmpAnswer(inPort, wMatch, frame, ICMP.TYPE_UNREACH,
                 UNREACH_CODE.UNREACH_NET)

        /**
         * Will be called whenever an ICMP Unreachable host is needed for the
         * given IP version.
         */
        def unreachableHostIcmp(inPort: RouterPort,
                                wMatch: FlowMatch,
                                frame: Ethernet)
                               (implicit originalPktContex: PacketContext) =
            icmpAnswer(inPort, wMatch, frame, ICMP.TYPE_UNREACH,
                 UNREACH_CODE.UNREACH_HOST)

        def unreachableFragNeededIcmp(inPort: RouterPort,
                                  wMatch: FlowMatch,
                                  frame: Ethernet)
                                 (implicit originalPktContext: PacketContext) =
            icmpAnswer(inPort, wMatch, frame, ICMP.TYPE_UNREACH,
                       UNREACH_CODE.UNREACH_FRAG_NEEDED)

        /**
         * Will be called whenever an ICMP Time Exceeded is needed for the given
         * IP version.
         */
        def timeExceededIcmp(inPort: RouterPort,
                             wMatch: FlowMatch,
                             frame: Ethernet)
                            (implicit originalPktContex: PacketContext) =
            icmpAnswer(inPort, wMatch, frame, ICMP.TYPE_TIME_EXCEEDED,
                 EXCEEDED_CODE.EXCEEDED_TTL)
    }

    implicit object IPv4Icmp extends IcmpErrorSender[IPv4Addr] {

        override def icmpAnswer(inPort: RouterPort,
                                ingressMatch: FlowMatch,
                                packet: Ethernet,
                                icmpType: Byte,
                                icmpCode: Any)
                               (implicit context: PacketContext)
        : Option[Ethernet] = {
            context.log.debug("Prepare an ICMP response")
            // Check whether the original packet is allowed to trigger ICMP.
            if (inPort == null) {
                context.log.debug("Don't send ICMP since inPort is null.")
                return None
            }
            if (!canSend(packet, inPort)) {
                context.log.debug("ICMP not allowed for this packet.")
                return None
            }
            // Build the ICMP packet from inside-out: ICMP, IPv4, Ethernet headers.
            context.log.debug(s"Generating ICMP error $icmpType:$icmpCode")
            val icmp = buildError(icmpType, icmpCode, ingressMatch, packet)
            if (icmp == null)
                return None

            val ip = new IPv4()
            ip.setPayload(icmp)
            ip.setProtocol(ICMP.PROTOCOL_NUMBER)
            // The nwDst is the source of triggering IPv4 as seen by this router.
            ip.setDestinationAddress(ingressMatch.getNetworkSrcIP
                    .asInstanceOf[IPv4Addr])
            // The nwSrc is the address of the ingress port.
            ip.setSourceAddress(inPort.portAddr.getAddress)
            val eth = new Ethernet()
            eth.setPayload(ip)
            eth.setEtherType(IPv4.ETHERTYPE)
            eth.setSourceMACAddress(inPort.portMac)
            eth.setDestinationMACAddress(ingressMatch.getEthSrc)

            // TODO(pino): check with Guillermo about match's vs.
            // device's inPort. ingressMatch.getInputPortUUID, eth)
            Some(eth)
        }

        private def buildError(icmpType: Byte, icmpCode: Any,
                               forMatch: FlowMatch, forPacket: Ethernet)
        : ICMP = {
            // TODO(pino, guillermo, jlm): original or modified trigger pkt?
            val pktHere = forPacket //forMatch.apply(forPacket)
            var ipPkt: IPv4 = null
            pktHere.getPayload match {
                case ip: IPv4 => ipPkt = ip
                case _ => return null
            }

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
         * @param ethPkt
         *        We wish to know whether this packet may trigger an
         *        ICMP error message.
         * @param outPort
         *        If known, this is the port that would have emitted the packet.
         *            It's used to determine whether the packet was addressed to an
         *            IP (local subnet) broadcast address.
         * @return True if-and-only-if the packet meets none of the above conditions
         *         - i.e. it can trigger an ICMP error message.
         */
        private def canSend(ethPkt: Ethernet, outPort: RouterPort)
                           (implicit context: PacketContext): Boolean = {
            var ipPkt: IPv4 = null
            ethPkt.getPayload match {
                case ip: IPv4 => ipPkt = ip
                case _ => return false
            }

            // Ignore ICMP errors.
            if (ipPkt.getProtocol == ICMP.PROTOCOL_NUMBER) {
                ipPkt.getPayload match {
                    case icmp: ICMP if icmp.isError =>
                        context.log.debug(
                            "Skipping generation of ICMP error for ICMP error packet")
                        return false
                    case _ =>
                }
            }
            // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
            // Ignore packets to IP mcast addresses.
            if (ipPkt.isMcast) {
                context.log.debug("Not generating ICMP Unreachable for packet "+
                                  "to an IP multicast address.")
                return false
            }
            // Ignore packets sent to the local-subnet IP broadcast address of the
            // intended egress port.
            if (null != outPort && outPort.portAddr.isInstanceOf[IPv4Subnet] &&
                    ipPkt.getDestinationIPAddress ==
                            outPort.portAddr.asInstanceOf[IPv4Subnet]
                                    .toBroadcastAddress) {
                context.log.debug("Not generating ICMP Unreachable for packet to "
                                + "the subnet local broadcast address.")
                return false
            }
            // Ignore packets to Ethernet broadcast and multicast addresses.
            if (ethPkt.isMcast) {
                context.log.debug("Not generating ICMP Unreachable for packet to "
                                + "Ethernet broadcast or multicast address.")
                return false
            }
            // Ignore packets with source network prefix zero or invalid source.
            // TODO(pino): See RFC 1812 sec. 5.3.7
            if (ipPkt.getSourceAddress == 0xffffffff
                    || ipPkt.getDestinationAddress == 0xffffffff) {
                context.log.debug(
                    "Not generating ICMP Unreachable for all-hosts broadcast packet")
                return false
            }
            // TODO(pino): check this fragment offset
            // Ignore datagram fragments other than the first one.
            if (0 != (ipPkt.getFragmentOffset & 0x1fff)) {
                context.log.debug(
                    "Not generating ICMP Unreachable for IP fragment packet")
                return false
            }

            true
        }
    }
}
