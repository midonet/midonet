// Copyright 2012 Midokura Inc.

package com.midokura.midolman.vrn

import akka.actor.{Actor, ActorRef}
import compat.Platform.currentTime
import scala.collection.Iterator
import scala.collection.immutable.List
import scala.collection.mutable.{HashMap, Map, MultiMap, Set}
import java.util.{ArrayList, UUID}
import java.util.{List => JavaList}

import com.midokura.util.netlink.dp.Packet
import com.midokura.util.netlink.dp.{Flow => KernelFlow}
import com.midokura.util.netlink.dp.flows.{FlowAction, FlowKey}
import com.midokura.util.netlink.dp.{FlowMatch => KernelMatch}
import com.midokura.midolman.openflow.MidoMatch
import com.midokura.midolman.packets.Ethernet


abstract class ControllerActorRequest
case class PacketIn(packet: Packet) extends ControllerActorRequest
case class SimulationDone(result: WildcardFlow, packet: Packet) 
        extends ControllerActorRequest
case class EmitGeneratedPacket(dpPort: Short, frame: Ethernet)


//XXX: Move into separate file
class WildcardFlow(val wcmatch: MidoMatch, val actions: List[FlowAction[_]],
                   val priority: Int) {
    val creationTime = currentTime
    private var lastUsedTime = creationTime

    def getLastUsedTime() = lastUsedTime
    def setLastUsedTime(timestamp: Long) { 
        if (timestamp > lastUsedTime)
            lastUsedTime = timestamp
    }
}

abstract class WildcardFlowManager {
    def matchPacket(packet: Packet): (WildcardFlow, KernelFlow)
    // returns evicted flows
    def add(flow: WildcardFlow): Set[WildcardFlow]
    def remove(wcmatch: MidoMatch): Unit
    def markUsed(flow: WildcardFlow): Unit
    def markUnused(flows: Set[WildcardFlow]): Unit
}

abstract class ExactFlowManager {
    // Returns evicted WildcardFlows
    def add(wcflow: WildcardFlow, flow: KernelFlow): 
                                (Set[WildcardFlow], Set[KernelFlow])

    def removeByWildcard(wcflow: WildcardFlow): Set[KernelFlow]
    def get(packet: Packet): KernelFlow
}

abstract class ValidationEngine {
    def add(wcflow: WildcardFlow, devices: Set[UUID]): Unit
    def remove(wcflow: WildcardFlow): Unit
    def getFlows(id: UUID): Set[WildcardFlow]
    // Older by creation time
    def getFlowsOlderThan(timestamp: Long): Iterator[WildcardFlow]
    def getFlowsOlderThan(id: UUID, timestamp: Long): Iterator[WildcardFlow]
}


/*
class KernelMatch(keys: JavaList[FlowKey[_ <: FlowKey[Any]]]) { }
object KernelMatch {
    implicit def fromKeyList(keys: JavaList[FlowKey[_ <: FlowKey[_]]]) =
        new KernelMatch(keys)
}
*/

class ControllerActor(XXX: Unit) extends Actor {
    private val pendedMatches: MultiMap[KernelMatch, Packet] =
        new HashMap[KernelMatch, Set[Packet]] with MultiMap[KernelMatch, Packet]
    private var wildcardFlowManager: WildcardFlowManager = _
    private var exactFlowManager: ExactFlowManager = _


    // Callback invoked from select-loop thread context.
    def onPacketIn(packet: Packet) {
        self ! PacketIn(packet)
    }

    // TODO(jlm, pino): Verify these calls are nonblocking.
    // Send the packet to be processed by the flow rule
    private def packetOut(packet: Packet, flow: KernelFlow) {
        //XXX
    }

    // Install the flow in the datapath, and send packet to be processed by it.
    private def installFlow(flow: KernelFlow, packet: Packet) {
        //XXX
    }

    // Remove the flow from the datapath
    private def removeFlow(flow: KernelFlow) {
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

        // Query the WildcardFlowManager 
        val flows = wildcardFlowManager.matchPacket(packet)
        if (flows._2 != null) {
            val evictedFlows = exactFlowManager.add(flows._1, flows._2)
            installFlow(flows._2, packet)
            wildcardFlowManager.markUnused(evictedFlows._1)
            for (kernelFlow <- evictedFlows._2) {
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
                    for (unpendedPacket <- pendedPackets.get)
                        packetOut(unpendedPacket, kernelFlow)

                val evictedWcFlows = wildcardFlowManager.add(wildcardFlow)
                val evictedKernelFlows = (
                        (Set[KernelFlow]() /: evictedWcFlows)
                                (_ ++ exactFlowManager.removeByWildcard(_)))
                for (kernelFlow <- evictedKernelFlows)
                    removeFlow(kernelFlow)
            }
            pendedMatches.remove(kernelMatch)

        case EmitGeneratedPacket(dpPort, ethPacket) =>
            // XXX
    }

}
