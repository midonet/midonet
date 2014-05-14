/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import org.midonet.odp.Packet
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActionSetKey
import org.midonet.odp.flows.FlowKeyICMPError
import org.midonet.odp.flows.FlowKeyICMPEcho
import org.midonet.packets.{ICMP, IPv4, Ethernet}

object UserspaceFlowActionTranslator {

    /**
     * This method applies actions related to UserSpaceKeys to a Packet. Cases
     * supported should be the ones that match on keys that implement
     * @see(FlowKey.UserSpaceOnly).
     *
     * The non-kernel-compatible actions will be removed from the
     * packet's actions list.
     *
     * This method is only intended to be called just before telling the
     * DP to execute the packet.
     *
     * @param packet
     * @return A newly allocated packet with the fields in the match applied
     * @throws MalformedPacketException
     */
    def translate(packet: Packet, actions: java.util.List[FlowAction]) = {
        val newActions = new java.util.ArrayList[FlowAction]()
        val iter = actions.iterator()
        while (iter.hasNext()) {
            iter.next() match {
                case a: FlowActionSetKey =>
                    a.getFlowKey match {
                        case k: FlowKeyICMPError =>
                            mangleIcmp(packet.getPacket, k.getIcmpData)
                        case k: FlowKeyICMPEcho =>
                        case _ =>
                            newActions.add(a)
                    }
                case a =>
                    newActions.add(a)
            }
        }
        newActions
    }

    // This is very limited but we don't really need more
    // This method takes a Ethernet packet and modifies it if it carries an
    // icmp payload
    private def mangleIcmp(eth: Ethernet, data: Array[Byte]) {
        eth.getPayload match {
            case ipv4: IPv4 =>
                ipv4.getPayload match {
                    case icmp: ICMP =>
                        icmp.setData(data)
                        ipv4.setPayload(icmp)
                        eth.setPayload(ipv4)
                    case _ =>
                }
            case _ =>
        }
    }
}
