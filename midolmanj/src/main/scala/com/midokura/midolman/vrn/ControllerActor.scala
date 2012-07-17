// Copyright 2012 Midokura Inc.

package com.midokura.midolman.vrn

import akka.actor.{Actor, ActorRef}
import scala.collection.mutable.{Map, Set}
import java.util.ArrayList

import com.midokura.util.netlink.dp.Packet
import com.midokura.midolman.openflow.MidoMatch


abstract class ControllerActorRequest
case class PacketIn(packet: Packet) extends ControllerActorRequest
case class SimulationDone(result: Unit) extends ControllerActorRequest

class ControllerActor(XXX: Unit) extends Actor {
    type FlowID = Int
    type WCFlow = MidoMatch
    type KernelFlow = Unit // XXX: A flow as represented by the datapath
    private var wcFlowToFlowIDs: Map[WCFlow, Set[FlowID]] = _
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
