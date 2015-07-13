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

package org.midonet.midolman.openstack.metadata

import akka.actor.ActorSystem

import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.PacketWorkflow.Drop
import org.midonet.midolman.PacketWorkflow.NoOp
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowActions.output
import org.midonet.packets.IPv4
import org.midonet.packets.IPv4Addr
import org.midonet.packets.TCP

object MetadataServiceWorkflow {
    var mdInfo: ProxyInfo = null
}

// Note: Ingress/Egress here are from the POV of metadata proxy, not VMs.

trait MetadataServiceWorkflow {
    import MetadataServiceWorkflow.mdInfo  // XXX needs some clean up

    def handleMetadataIngress(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        val fmatch = context.wcmatch

        if (!matchIngress(fmatch)) {
            return NoOp  // not ours; fallback to other handlers
        }

        val remoteAddr =
            AddressManager dpPortToRemoteAddress fmatch.getInputPortNumber

        context.log.debug(s"MetadataIngress: remoteAddr ${remoteAddr}")
        // Do not wildcard src address
        fmatch.getNetworkSrcIP
        fmatch.getSrcPort
        fmatch.setEthDst(mdInfo.mac)
        fmatch.setNetworkSrc(IPv4Addr(remoteAddr))
        fmatch.setDstPort(Proxy.port)
        context.calculateActionsFromMatchDiff
        context.addVirtualAction(output(mdInfo.dpPortNo))
        AddVirtualWildcardFlow
    }

    def handleMetadataEgress(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        val fmatch = context.wcmatch

        if (fmatch.getInputPortNumber != mdInfo.dpPortNo) {
            return NoOp  // not ours; fallback to other handlers
        }

        if (!matchEgress(fmatch)) {
            context.log.debug("MetadataEgress: no match")
            return Drop
        }

        val remoteAddr = fmatch.getNetworkDstIP.toString

        InstanceInfoMap getByAddr remoteAddr match {
            case Some(vmInfo) =>
                val vmDpPortNo =
                    AddressManager remoteAddressToDpPort remoteAddr

                context.log.debug("MetadataEgress: " +
                                  s"remoteAddr ${remoteAddr} info ${vmInfo}" +
                                  s"vm-port ${vmDpPortNo}")
                // Do not wildcard dst address
                fmatch.getNetworkDstIP
                fmatch.getDstPort
                fmatch.setEthDst(vmInfo.mac)
                fmatch.setNetworkDst(IPv4Addr(vmInfo.addr))
                fmatch.setSrcPort(MetadataApi.port)
                context.calculateActionsFromMatchDiff
                context.addVirtualAction(output(vmDpPortNo))
                AddVirtualWildcardFlow
            case None =>
                Drop
        }
    }

    private def matchIngress(fmatch: FlowMatch) = {
        fmatch.getEtherType == IPv4.ETHERTYPE &&
        fmatch.getNetworkDstIP == IPv4Addr(MetadataApi.address) &&
        fmatch.getNetworkProto == TCP.PROTOCOL_NUMBER &&
        fmatch.getDstPort == MetadataApi.port
    }

    private def matchEgress(fmatch: FlowMatch) = {
        fmatch.getEtherType == IPv4.ETHERTYPE &&
        fmatch.getNetworkSrcIP == IPv4Addr(MetadataApi.address) &&
        fmatch.getNetworkProto == TCP.PROTOCOL_NUMBER &&
        fmatch.getSrcPort == Proxy.port
    }
}
