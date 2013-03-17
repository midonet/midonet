// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import scala.collection.JavaConversions._

import org.midonet.odp._
import org.midonet.odp.flows.{FlowActionSetKey, FlowKeyICMPError, FlowKeyICMPEcho}
import org.midonet.packets.{ICMP, IPv4, Ethernet}


trait UserspaceFlowActionTranslator {
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
    def applyActionsAfterUserspaceMatch(packet: Packet) {
        def mangleIcmp(data: Array[Byte]): Option[Ethernet] = {
            // This is very limited but we don't really need more
            val eth = Ethernet.deserialize(packet.getData)
            eth.getPayload match {
                case ipv4: IPv4 =>
                    ipv4.getPayload match {
                        case icmp: ICMP =>
                            icmp.setData(data)
                            ipv4.setPayload(icmp)
                            eth.setPayload(ipv4)
                            Some(eth)
                        case _ =>
                            None
                    }
                case _ =>
                    None
            }
        }

        val newActions = packet.getActions filter {_ match {
            case a: FlowActionSetKey => a.getFlowKey match {
                case k: FlowKeyICMPError =>
                    mangleIcmp(k.getIcmpData) match {
                        case Some(eth) => packet.setData(eth.serialize())
                        case None =>
                    }
                    false
                case k: FlowKeyICMPEcho =>
                    false
                case _ =>
                    true
                }
            case _ =>
                true
        }}

        newActions
    }
}
