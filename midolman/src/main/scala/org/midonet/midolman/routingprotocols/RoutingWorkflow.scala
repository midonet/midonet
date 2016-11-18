/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.routingprotocols

import java.util.{Collections, UUID}
import java.util.concurrent.ConcurrentHashMap

import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, NoOp, SimulationResult, ErrorDrop}
import org.midonet.midolman.simulation.{RouterPort, PacketContext}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.FlowActions.{output, userspace}
import org.midonet.packets._

object RoutingWorkflow {
    private def makeIpSet: java.util.Set[IPv4Addr] =
        Collections.newSetFromMap(new ConcurrentHashMap[IPv4Addr, java.lang.Boolean]())

    case class RoutingInfo(portId: UUID, var uplinkPid: Int,
                           var dpPortNo: Int,
                           peers: java.util.Set[IPv4Addr] = makeIpSet)

    val routerPortToDatapathInfo = new ConcurrentHashMap[UUID, RoutingInfo]()
    val inputPortToDatapathInfo = new ConcurrentHashMap[Int, RoutingInfo]()
}

trait RoutingWorkflow {
    import RoutingWorkflow._

    /**
     * Handles BGP traffic coming in through Quagga.
     */
    def handleBgp(context: PacketContext, inPort: Int)
            : SimulationResult = {
        val info = inputPortToDatapathInfo.get(inPort)
        if (info eq null)
            return ErrorDrop

        val port = VirtualTopology.tryGet(classOf[RouterPort], info.portId)
        if (context.wcmatch.getEtherType == ARP.ETHERTYPE ||
            matchBgp(context, info, port)) {

            context.log.debug(s"Packet matched BGP traffic at port: ${info.portId}")
            context.addVirtualAction(port.action)
            AddVirtualWildcardFlow
        } else {
            context.log.debug(s"BGP traffic not recognized at port ${info.portId}")
            ErrorDrop
        }
    }

    /**
     * Handles BGP traffic coming in through the Virtual Topology, that
     * should be sent to Quagga.
     */
    def handleBgp(context: PacketContext, port: RouterPort): SimulationResult = {
        val info = routerPortToDatapathInfo.get(port.id)
        if (info eq null) {
            return NoOp
        }

        if (matchBgp(context, info, port)) {
            context.addVirtualAction(output(info.dpPortNo))
            /*
             * Ensures ARP replies always seen to userspace
             * so that the router can learn the MAC address.
             */
            if (context.wcmatch.getEtherType == ARP.ETHERTYPE) {
               context.addVirtualAction(userspace(info.uplinkPid))
            }
            AddVirtualWildcardFlow
        } else {
            context.log.debug(s"BGP traffic not recognized at port ${port.id}")
            NoOp
        }
    }

    private def matchBgp(context: PacketContext, bgp: RoutingInfo, port: RouterPort): Boolean = {
        val fmatch = context.wcmatch
        matchProto(fmatch, bgp, port) || matchArp(fmatch, bgp, port)
    }

    private def matchProto(fmatch: FlowMatch, bgp: RoutingInfo, port: RouterPort) =
        fmatch.getEtherType == IPv4.ETHERTYPE &&  matchNetwork(fmatch, bgp, port) &&
            ((fmatch.getNetworkProto == TCP.PROTOCOL_NUMBER && matchTcp(fmatch)) ||
             fmatch.getNetworkProto == ICMP.PROTOCOL_NUMBER)

    private def matchTcp(fmatch: FlowMatch) =
        if (fmatch.getSrcPort == RoutingHandler.BgpTcpPort) {
            true
        } else if (fmatch.getDstPort == RoutingHandler.BgpTcpPort) {
            fmatch.fieldUnseen(Field.SrcPort)
            true
        } else {
            false
        }

    private def matchArp(fmatch: FlowMatch, bgp: RoutingInfo, port: RouterPort) =
        fmatch.getEtherType == ARP.ETHERTYPE &&
        fmatch.getEthDst == port.portMac &&
        fmatch.getNetworkProto == ARP.OP_REPLY.toByte &&
        matchNetwork(fmatch, bgp, port)

    private def matchNetwork(fmatch: FlowMatch, bgp: RoutingInfo, port: RouterPort) =
        (port.portAddress4 ne null) &&
        ((bgp.peers.contains(fmatch.getNetworkSrcIP) &&
          fmatch.getNetworkDstIP == port.portAddress4.getAddress) ||
         (fmatch.getNetworkSrcIP == port.portAddress4.getAddress &&
          bgp.peers.contains(fmatch.getNetworkDstIP)))
}
