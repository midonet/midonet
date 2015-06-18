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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem

import org.midonet.cluster.data.BGP
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, NoOp, SimulationResult, ErrorDrop}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.FlowActions.{output, userspace}
import org.midonet.packets.{ARP, ICMP, IPv4, TCP}
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort

object RoutingWorkflow {
    val inputPortToBgp = new ConcurrentHashMap[Int, BGP]
    val routerPortToBgp = new ConcurrentHashMap[UUID, BGP]()
}

trait RoutingWorkflow {
    import RoutingWorkflow._

    /**
     * Handles BGP traffic coming in through Quagga.
     */
    def handleBgp(context: PacketContext, inPort: Int)
                 (implicit as: ActorSystem): SimulationResult = {
        val bgp = inputPortToBgp.get(inPort)
        if (bgp eq null) {
            return ErrorDrop
        }

        val port = VirtualTopologyActor.tryAsk[RouterPort](bgp.getPortId)
        if (context.wcmatch.getEtherType == ARP.ETHERTYPE ||
            matchBgp(context, bgp, port)) {

            context.log.debug(s"Packet matched BGP traffic for $bgp")
            context.addVirtualAction(FlowActionOutputToVrnPort(port.id))
            AddVirtualWildcardFlow
        } else {
            context.log.debug(s"BGP traffic not recognized for $bgp")
            ErrorDrop
        }
    }

    /**
     * Handles BGP traffic coming in through the Virtual Topology, that
     * should be sent to Quagga.
     */
    def handleBgp(context: PacketContext, port: RouterPort): SimulationResult = {
        val bgp = routerPortToBgp.get(port.id)
        if (bgp eq null) {
            return NoOp
        }

        if (matchBgp(context, bgp, port)) {
            context.addVirtualAction(output(bgp.getQuaggaPortNumber()))
            if (context.wcmatch.getEtherType == ARP.ETHERTYPE) {
               context.addVirtualAction(userspace(bgp.getUplinkPid))
            }
            AddVirtualWildcardFlow
        } else {
            context.log.debug(s"BGP traffic not recognized for $bgp")
            NoOp
        }
    }

    private def matchBgp(context: PacketContext, bgp: BGP, port: RouterPort): Boolean = {
        val fmatch = context.wcmatch
        matchProto(fmatch, bgp, port) || matchArp(fmatch, bgp, port)
    }

    private def matchProto(fmatch: FlowMatch, bgp: BGP, port: RouterPort) =
        fmatch.getEtherType == IPv4.ETHERTYPE &&  matchNetwork(fmatch, bgp, port) &&
            ((fmatch.getNetworkProto == TCP.PROTOCOL_NUMBER && matchTcp(fmatch)) ||
             fmatch.getNetworkProto == ICMP.PROTOCOL_NUMBER)

    private def matchTcp(fmatch: FlowMatch) =
        if (fmatch.getSrcPort == RoutingHandler.BGP_TCP_PORT) {
            true
        } else if (fmatch.getDstPort == RoutingHandler.BGP_TCP_PORT) {
            fmatch.fieldUnseen(Field.SrcPort)
            true
        } else {
            false
        }

    private def matchArp(fmatch: FlowMatch, bgp: BGP, port: RouterPort) =
        fmatch.getEtherType == ARP.ETHERTYPE &&
        fmatch.getEthDst == port.portMac &&
        fmatch.getNetworkProto == ARP.OP_REPLY.toByte &&
        matchNetwork(fmatch, bgp, port)

    private def matchNetwork(fmatch: FlowMatch, bgp: BGP, port: RouterPort) =
        (fmatch.getNetworkSrcIP == bgp.getPeerAddr &&
         fmatch.getNetworkDstIP == port.portAddr.getAddress) ||
        (fmatch.getNetworkSrcIP == port.portAddr.getAddress &&
         fmatch.getNetworkDstIP == bgp.getPeerAddr)
}
