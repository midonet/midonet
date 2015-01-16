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

package org.midonet.midolman.routingprotocols

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import org.midonet.cluster.data.BGP
import org.midonet.midolman.PacketWorkflow.{TemporaryDrop, AddVirtualWildcardFlow, SimulationResult}
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
}

trait RoutingWorkflow {
    import RoutingWorkflow._

    def handleBgp(context: PacketContext, inPort: Int)
                 (implicit as: ActorSystem): SimulationResult = {
        val bgp = inputPortToBgp.get(inPort)
        if (bgp eq null)
            return TemporaryDrop

        val port = VirtualTopologyActor.tryAsk[RouterPort](bgp.getPortId)
        if (context.wcmatch.getEtherType == ARP.ETHERTYPE ||
            handleBgp(context, bgp, port)) {

            context.addVirtualAction(FlowActionOutputToVrnPort(port.id))
            AddVirtualWildcardFlow
        } else {
            TemporaryDrop
        }
    }

    def handleBgp(context: PacketContext, port: RouterPort): SimulationResult = {
        val it = port.bgps.iterator()
        while (it.hasNext) {
            val bgp = it.next()
            if (handleBgp(context, bgp, port)) {
                context.addVirtualAction(output(bgp.getQuaggaPortNumber()))
                if (context.wcmatch.getEtherType == ARP.ETHERTYPE) {
                    context.addVirtualAction(userspace(bgp.getUplinkPid()))
                }
                return AddVirtualWildcardFlow
            }
        }
        TemporaryDrop
    }

    private def handleBgp(context: PacketContext, bgp: BGP, port: RouterPort): Boolean = {
        val fmatch = context.wcmatch
        if (!matchNetwork(fmatch, bgp, port))
            return false

        if (fmatch.getEtherType == IPv4.ETHERTYPE) {
            if (fmatch.getNetworkProto == TCP.PROTOCOL_NUMBER) {
                if (fmatch.getSrcPort == RoutingHandler.BGP_TCP_PORT) {
                    true
                } else if (fmatch.getDstPort == RoutingHandler.BGP_TCP_PORT) {
                    fmatch.fieldUnseen(Field.SrcPort)
                    true
                } else {
                    false
                }
            } else {
                fmatch.getNetworkProto == ICMP.PROTOCOL_NUMBER
            }
        } else {
            fmatch.getEtherType == ARP.ETHERTYPE &&
            fmatch.getEthDst == port.portMac &&
            fmatch.getNetworkProto == ARP.OP_REPLY.toByte
        }
    }

    private def matchNetwork(fmatch: FlowMatch, bgp: BGP, port: RouterPort) =
        (fmatch.getNetworkSrcIP == bgp.getPeerAddr &&
         fmatch.getNetworkDstIP == port.portAddr.getAddress) ||
        (fmatch.getNetworkSrcIP == port.portAddr.getAddress &&
         fmatch.getNetworkSrcIP == bgp.getPeerAddr)
}
