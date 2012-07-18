// Copyright 2012 Midokura Inc.

package com.midokura.midolman.vrn

import akka.actor.{Actor, ActorRef}
import compat.Platform.currentTime
import scala.collection.Iterator
import scala.collection.immutable.List
import scala.collection.mutable.{Map, Set}
import java.util.{ArrayList, UUID}

import com.midokura.util.netlink.dp.Packet
import com.midokura.util.netlink.dp.{Flow => KernelFlow}
import com.midokura.util.netlink.dp.flows.FlowAction
import com.midokura.midolman.openflow.MidoMatch


abstract class ControllerActorRequest
case class PacketIn(packet: Packet) extends ControllerActorRequest
case class SimulationDone(result: KernelFlow) extends ControllerActorRequest

//XXX: Move into separate file
class WildcardFlow(val wcmatch: MidoMatch, val actions: List[FlowAction[_]]) {
    val creationTime = currentTime
    private var lastUsedTime = creationTime

    def getLastUsedTime() = lastUsedTime
    def setLastUsedTime(timestamp: Long) { 
        if (timestamp > lastUsedTime)
            lastUsedTime = timestamp
    }
}

abstract class WildcardFlowManager {
    def matchPacket(packet: Packet): KernelFlow
    def add(flow: WildcardFlow): Unit
    def remove(wcmatch: MidoMatch): Unit
    def markUsed(flow: WildcardFlow): Unit
    def markUnused(flows: Set[WildcardFlow]): Unit
}

abstract class ExactFlowManager {
    // Returns evicted WildcardFlows
    def add(wcflow: WildcardFlow, flow: KernelFlow): Set[WildcardFlow]

    def removeByWildcard(wcflow: WildcardFlow): Unit
    def contains(flow: KernelFlow): Boolean
}

abstract class ValidationEngine {
    def add(wcflow: WildcardFlow, devices: Set[UUID]): Unit
    def remove(wcflow: WildcardFlow): Unit
    def getFlows(id: UUID): Set[WildcardFlow]
    // Older by creation time
    def getFlowsOlderThan(timestamp: Long): Iterator[WildcardFlow]
    def getFlowsOlderThan(id: UUID, timestamp: Long): Iterator[WildcardFlow]
}


class ControllerActor(XXX: Unit) extends Actor {
    type FlowID = Int
    type WildcardFlow = MidoMatch
    private var wcFlowToFlowIDs: Map[WildcardFlow, Set[FlowID]] = _
    private var flowIDToKernelFlow: Map[FlowID, KernelFlow] = _
    private var installedFlowIDs: ArrayList[FlowID] = _


    // Callback invoked from select-loop thread context.
    def onPacketIn(packet: Packet) {
        self ! PacketIn(packet)
    }

    def receive = {
        case PacketIn(packet) =>
            // check if packet matches an entry in wcFlowToFlowIDs and
            // in that case immediately install a flow.
            // Otherwise, constructs and kick off a SimulationActor.
        case SimulationDone(result) =>
            // install (or don't, depending on result type) rule for enacting
            // what the simulation did.
    }

}
