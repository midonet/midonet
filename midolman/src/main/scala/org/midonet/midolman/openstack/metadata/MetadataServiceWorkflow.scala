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
import org.midonet.midolman.PacketWorkflow.ShortDrop
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowActions.output
import org.midonet.packets.ARP
import org.midonet.packets.IPv4
import org.midonet.packets.IPv4Addr
import org.midonet.packets.MAC
import org.midonet.packets.TCP

object MetadataServiceWorkflow {
    var mdInfo: ProxyInfo = null  /* null means diabled */
}

// Note: Ingress/Egress here are from the POV of metadata proxy, not VMs.

trait MetadataServiceWorkflow {
    import MetadataServiceWorkflow.mdInfo

    def handleMetadataIngress(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        if (mdInfo eq null) {
            return null  // disabled
        }
        val fmatch = context.wcmatch
        if (matchIngressTcp(fmatch)) {
            handleMetadataIngressTcp(context)
        } else if (matchIngressArp(fmatch)) {
            handleMetadataIngressArp(context)
        } else {
            null  // not ours; fallback to other handlers
        }
    }

    private def handleMetadataIngressTcp(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        val fmatch = context.wcmatch
        val remoteAddr =
            AddressManager dpPortToRemoteAddress fmatch.getInputPortNumber

        context.log debug s"MetadataIngress: remoteAddr ${remoteAddr}"
        // Do not wildcard src address
        fmatch.fieldSeen(FlowMatch.Field.NetworkSrc)
        fmatch.fieldSeen(FlowMatch.Field.SrcPort)
        fmatch.setEthDst(mdInfo.mac)
        fmatch.setNetworkSrc(IPv4Addr(remoteAddr))
        fmatch.setDstPort(Proxy.port)
        context.calculateActionsFromMatchDiff
        context.addVirtualAction(output(mdInfo.dpPortNo))
        AddVirtualWildcardFlow
    }

    private def handleMetadataIngressArp(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        val remoteAddr = MetadataApi.address
        val mac = MAC fromString "94:e7:ac:7a:c5:13"  // random

        context.log debug "MetadataIngress: " +
                          s"Replying ARP: ${remoteAddr}/${mac}"
        context.addGeneratedPacket(context.inputPort,
                                   makeArpReply(context, mac))
        NoOp
    }

    private def matchIngressTcp(fmatch: FlowMatch) = {
        fmatch.getEtherType == IPv4.ETHERTYPE &&
        fmatch.getNetworkDstIP == IPv4Addr(MetadataApi.address) &&
        fmatch.getNetworkProto == TCP.PROTOCOL_NUMBER &&
        fmatch.getDstPort == MetadataApi.port
    }

    private def matchIngressArp(fmatch: FlowMatch) = {
        fmatch.getEtherType == ARP.ETHERTYPE &&
        fmatch.getNetworkProto == ARP.OP_REQUEST &&
        fmatch.getNetworkDstIP.toString == MetadataApi.address
    }

    def handleMetadataEgress(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        if (mdInfo eq null) {
            return null  // disabled
        }
        val fmatch = context.wcmatch
        if (fmatch.getInputPortNumber != mdInfo.dpPortNo) {
            return null  // not ours; fallback to other handlers
        }
        if (matchEgressArp(fmatch)) {
            handleMetadataEgressArp(context)
        } else if (matchEgressTcp(fmatch)) {
            handleMetadataEgressTcp(context)
        } else {
            context.log debug "MetadataEgress: no match"
            Drop
        }
    }

    private def handleMetadataEgressTcp(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        val fmatch = context.wcmatch
        val remoteAddr = fmatch.getNetworkDstIP.toString

        InstanceInfoMap getByAddr remoteAddr match {
            case Some(vmInfo) =>
                val vmDpPortNo =
                    AddressManager remoteAddressToDpPort remoteAddr

                context.log debug "MetadataEgress: " +
                                  s"remoteAddr ${remoteAddr} info ${vmInfo}" +
                                  s"vm-port ${vmDpPortNo}"
                // Do not wildcard dst address
                fmatch.fieldSeen(FlowMatch.Field.NetworkDst)
                fmatch.fieldSeen(FlowMatch.Field.DstPort)
                fmatch.setEthDst(vmInfo.mac)
                fmatch.setNetworkDst(IPv4Addr(vmInfo.addr))
                fmatch.setSrcPort(MetadataApi.port)
                context.calculateActionsFromMatchDiff
                context.addVirtualAction(output(vmDpPortNo))
                AddVirtualWildcardFlow
            case None =>
                /*
                 * MetadataServiceManagerActor might have not updated
                 * InstanceInfoMap yet.  Very unlikely but possible.
                 */
                context.log warn "MetadataEgress: " +
                                 s"Unknown remote ${remoteAddr}"
                ShortDrop
        }
    }

    private def handleMetadataEgressArp(context: PacketContext)
            (implicit as: ActorSystem): SimulationResult = {
        val fmatch = context.wcmatch
        val remoteAddr = fmatch.getNetworkDstIP.toString  // tpa

        InstanceInfoMap getByAddr remoteAddr match {
            case Some(vmInfo) =>
                /*
                 * The specific MAC address here doesn't actually matter
                 * because it's always wildcarded and overwritten by
                 * our flows anyway.
                 */
                context.log debug "MetadataEgress: " +
                                  s"Replying ARP: ${remoteAddr}/${vmInfo.mac}"
                val mac = MAC fromString vmInfo.mac
                context.addGeneratedPhysicalPacket(mdInfo.dpPortNo,
                                                   makeArpReply(context, mac))
                NoOp
            case None =>
                context.log warn "MetadataEgress: " +
                                 s"Unknown Arp tpa ${remoteAddr}"
                ShortDrop
        }
    }

    private def matchEgressTcp(fmatch: FlowMatch) = {
        fmatch.getEtherType == IPv4.ETHERTYPE &&
        fmatch.getNetworkSrcIP == IPv4Addr(MetadataApi.address) &&
        fmatch.getNetworkProto == TCP.PROTOCOL_NUMBER &&
        fmatch.getSrcPort == Proxy.port
    }

    private def matchEgressArp(fmatch: FlowMatch) = {
        fmatch.getEtherType == ARP.ETHERTYPE &&
        fmatch.getNetworkProto == ARP.OP_REQUEST
    }

    private def makeArpReply(context: PacketContext, mac: MAC) = {
        val arpReq = context.ethernet.getPayload.asInstanceOf[ARP]
        val tpa = arpReq.getTargetProtocolAddress
        ARP.makeArpReply(mac, arpReq.getSenderHardwareAddress, tpa,
                         arpReq.getSenderProtocolAddress)
    }
}
