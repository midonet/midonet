// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor.{ActorRef, Actor}
import collection.mutable.Set

import com.midokura.util.netlink.dp.{Packet}
import com.midokura.sdn.flows.{WildcardFlow, MidoMatch}
import java.util.UUID
import com.midokura.packets.Ethernet
import com.midokura.util.netlink.dp.flows.FlowAction


case class SimulationDone(originalMatch: MidoMatch, finalMatch: MidoMatch,
                          outPorts: Set[UUID], packet: Packet,
                          generated: Boolean)
case class EmitGeneratedPacket(vportID: UUID, frame: Ethernet)


class ControllerActor(val fController: ActorRef) extends Actor {
    fController ! RegisterPacketInListener(packetInCallback)

    case class PacketIn(packet: Packet, vportID: UUID)

    def packetInCallback(packet: Packet, id: UUID) {
        self ! PacketIn(packet, id)
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
                    fController ! SendPacket(packet.getData, actions, outPorts)
                }
                // Else, do nothing, the packet is dropped.
            }
            else if (finalMatch == null)
                fController ! Consume(packet)
            else {
                // TODO(pino, jlm): compute the WildcardFlow, including actions
                // XXX
                val wildcardFlow: WildcardFlow = null
                fController ! AddWildcardFlow(wildcardFlow, outPorts,
                    Some(packet))
            }
        case PacketIn(packet, vportID) =>
            // TODO(pino, jlm): launchNewSimulation(packet, vportID)
            // XXX
        case EmitGeneratedPacket(vportID, frame) =>
            // TODO(pino, jlm): do a new simulation.
            // XXX
    }

}
