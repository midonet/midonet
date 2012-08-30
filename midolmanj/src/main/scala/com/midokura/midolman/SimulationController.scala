// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor.{ActorLogging, Actor}
import java.util.UUID

import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.packets.Ethernet
import simulation.Coordinator
import com.midokura.sdn.flows.WildcardMatches

object SimulationController extends Referenceable {

    val Name = "SimulationController"

    case class EmitGeneratedPacket(egressPort: UUID, ethPkt: Ethernet)
}

class SimulationController() extends Actor with ActorLogging {
    import SimulationController._
    import context._

    def receive = {

        case PacketIn(packet, wMatch) =>
            Coordinator.simulate(wMatch, packet.getMatch,
                Ethernet.deserialize(packet.getData), null)

        case EmitGeneratedPacket(egressPort, ethPkt) =>
            Coordinator.simulate(WildcardMatches.fromEthernetPacket(ethPkt),
                null, ethPkt, egressPort)
    }
}
