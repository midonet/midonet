// Copyright 2012 Midokura Inc.

package com.midokura.midolman.vrn

import akka.actor.Actor
import collection.JavaConversions._
import collection.mutable.{HashMap, MultiMap, Set}
import java.util.{Set => JavaSet}

import com.midokura.util.netlink.dp.{Flow => KernelFlow,
                                     FlowMatch => KernelMatch, Packet}
import com.midokura.midolman.packets.Ethernet
import com.midokura.sdn.flows.{NetlinkFlowTable, WildcardFlowTable,
                               WildcardFlow}


abstract class ControllerActorRequest
case class PacketIn(packet: Packet) extends ControllerActorRequest
case class SimulationDone(result: WildcardFlow, packet: Packet)
        extends ControllerActorRequest
case class EmitGeneratedPacket(dpPort: Short, frame: Ethernet)


class ControllerActor(val wildcardFlowManager: WildcardFlowTable,
                      val exactFlowManager: NetlinkFlowTable) extends Actor {
    private val pendedMatches: MultiMap[KernelMatch, Packet] =
        new HashMap[KernelMatch, Set[Packet]] with MultiMap[KernelMatch, Packet]


    // Callback invoked from select-loop thread context.
    def onPacketIn(packet: Packet) {
        self ! PacketIn(packet)
    }

    // Send the packet to be processed by the flow rule
    private def packetOut(packet: Packet, flow: KernelFlow) {
        //XXX
    }

    // Install the flow in the datapath, and send packet to be processed by it.
    private def installFlow(flow: KernelFlow, packet: Packet) {
        //XXX
    }

    // Remove the flow from the datapath
    private def removeFlow(kMatch: KernelMatch) {
        //XXX
    }

    private def launchNewSimulation(packet: Packet) { /*XXX*/ }

    private def doPacketIn(packet: Packet) {
        // First check if packet matches an exact flow, in case
        // the PacketIn notify crosssed the flow's install message.
        val exactFlow = exactFlowManager.get(packet)
        if (exactFlow != null) {
            packetOut(packet, exactFlow)
            return
        }

        // Query the WildcardFlowTable
        val wFlow = wildcardFlowManager.matchPacket(packet)
        if (wFlow != null) {
            val kFlow = new KernelFlow().
                setMatch(packet.getMatch).setActions(wFlow.actions)
            val evictedFlows = exactFlowManager.add(wFlow, kFlow)
            installFlow(kFlow, packet)
            wildcardFlowManager.markUnused(evictedFlows.fst)
            for (kernelFlow <- evictedFlows.snd) {
                removeFlow(kernelFlow)
            }
        } else {
            val kernelMatch = packet.getMatch
            if (pendedMatches.get(kernelMatch) == None) {
                launchNewSimulation(packet)
            }
            pendedMatches.addBinding(kernelMatch, packet)
        }
    }

    def receive = {
        case PacketIn(packet) => doPacketIn(packet)

        case SimulationDone(wildcardFlow, packet) =>
            val kernelMatch = packet.getMatch
            val pendedPackets = pendedMatches.get(kernelMatch)
            // wildcardFlow == null  indicates packet is consumed without
            // a flow rule being installed in the datapath.
            if (wildcardFlow != null) {
                val kernelFlow = new KernelFlow()
                kernelFlow.setMatch(packet.getMatch)
                kernelFlow.setActions(packet.getActions)
                installFlow(kernelFlow, packet)
                // Send pended packets out the new rule
                if (pendedPackets != None)
                    for {unpendedPacket <- pendedPackets.get
                         if unpendedPacket != packet}
                        packetOut(unpendedPacket, kernelFlow)

                val evictedWcFlows = wildcardFlowManager.add(wildcardFlow)
                val evictedKernelFlows = (
                        (Set[KernelFlow]() /: evictedWcFlows)
                                (_ ++ exactFlowManager.removeByWildcard(_)))
                for (kernelFlow <- evictedKernelFlows)
                    removeFlow(kernelFlow.getMatch)
            }
            pendedMatches.remove(kernelMatch)

        case EmitGeneratedPacket(dpPort, ethPacket) =>
            // XXX
    }

}
