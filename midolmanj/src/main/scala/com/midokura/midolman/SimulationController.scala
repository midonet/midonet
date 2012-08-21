// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor.{ActorRef, Actor}

import openflow.MidoMatch
import com.midokura.sdn.flows.{WildcardFlow}
import java.util.UUID
import com.midokura.packets.Ethernet
import collection.mutable
import com.midokura.sdn.dp.Packet
import com.midokura.sdn.dp.flows.FlowAction
import com.midokura.midolman.FlowController.{AddWildcardFlow, Consume}
import com.midokura.midolman.FlowController.SendPacket
import akka.event.Logging

object SimulationController {

    val Name = "SimulationController"

    case class SimulationDone(originalMatch: MidoMatch, finalMatch: MidoMatch,
                              outPorts: mutable.Set[UUID], packet: Packet,
                              generated: Boolean)

    case class EmitGeneratedPacket(vportID: UUID, frame: Ethernet)
}

class SimulationController() extends Actor {
    import SimulationController._
    import context._

    val log = Logging(context.system, this)

    case class PacketIn(packet: Packet, vportID: UUID)

    def packetInCallback(packet: Packet, id: UUID) {
        self ! PacketIn(packet, id)
    }

    protected def fController():ActorRef = {
        actorFor("/user/%s" format FlowController.Name)
    }

    def receive = {
        case SimulationDone(originalMatch, finalMatch, outPorts,
                packet, generated) =>
            // Emtpy or null outPorts indicates Drop rule.
            // Null finalMatch indicates that the packet was consumed.
            if (generated) {
                if (null != finalMatch) {
                    // TODO(pino, jlm): diff matches to build action list
                    // XXX
                    val actions: List[FlowAction[_]] = null
                    fController ! SendPacket(packet.getData, actions)
                }
                // Else, do nothing, the packet is dropped.
            }
            else if (finalMatch == null)
                fController ! Consume(packet)
            else {
                // TODO(pino, jlm): compute the WildcardFlow, including actions
                // XXX
                val wildcardFlow: WildcardFlow = null
                fController ! AddWildcardFlow(wildcardFlow, Some(packet))
            }
        case PacketIn(packet, vportID) =>
            // TODO(pino, jlm): launchNewSimulation(packet, vportID)
            // XXX
        case EmitGeneratedPacket(vportID, frame) =>
            // TODO(pino, jlm): do a new simulation.
            // XXX
    }

}
