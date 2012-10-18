/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.util

import java.util.UUID

import com.midokura.packets._
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.midolman.MidolmanTestCase
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.AddWildcardFlow

trait SimulationHelper extends MidolmanTestCase {

    final val IPv6_ETHERTYPE: Short = 0x86dd.toShort

    def expectPacketOnPort(port: UUID): PacketIn = {
        dpProbe().expectMsgClass(classOf[PacketIn])

        val pktInMsg = simProbe().expectMsgClass(classOf[PacketIn])
        pktInMsg should not be null
        pktInMsg.pktBytes should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.wMatch.getInputPortUUID should be === port
        pktInMsg
    }

    def expectFlowAddedMessage(): WildcardFlow = {
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow
    }

    def expectMatchForIPv4Packet(pkt: Ethernet, wmatch: WildcardMatch) {
        wmatch.getEthernetDestination should be === pkt.getDestinationMACAddress
        wmatch.getEthernetSource should be === pkt.getSourceMACAddress
        wmatch.getEtherType should be === pkt.getEtherType
        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
        wmatch.getNetworkDestination should be === ipPkt.getDestinationAddress
        wmatch.getNetworkSource should be === ipPkt.getSourceAddress
        wmatch.getNetworkProtocol should be === ipPkt.getProtocol

        ipPkt.getProtocol match {
            case UDP.PROTOCOL_NUMBER =>
                val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
                wmatch.getTransportDestination should be === udpPkt.getDestinationPort
                wmatch.getTransportSource should be === udpPkt.getSourcePort
            case TCP.PROTOCOL_NUMBER =>
                val tcpPkt = ipPkt.getPayload.asInstanceOf[TCP]
                wmatch.getTransportDestination should be === tcpPkt.getDestinationPort
                wmatch.getTransportSource should be === tcpPkt.getSourcePort
            case _ =>
        }
    }
}
