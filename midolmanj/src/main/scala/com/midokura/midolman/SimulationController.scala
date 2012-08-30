// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import compat.Platform
import akka.actor.{Actor, ActorLogging}
import akka.util.duration._
import java.util.UUID

import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.simulation.Coordinator
import com.midokura.packets.Ethernet
import com.midokura.sdn.flows.WildcardMatches


object SimulationController extends Referenceable {
    val Name = "SimulationController"

    case class EmitGeneratedPacket(egressPort: UUID, ethPkt: Ethernet)
}

class SimulationController() extends Actor with ActorLogging {
    import SimulationController._
    import context._

    val timeout = (5 minutes).toMillis

    def receive = {
        case PacketIn(packet, wMatch) =>
            Coordinator.simulate(wMatch, packet.getMatch,
                Ethernet.deserialize(packet.getData), null,
                Platform.currentTime + timeout)

        case EmitGeneratedPacket(egressPort, ethPkt) =>
            Coordinator.simulate(WildcardMatches.fromEthernetPacket(ethPkt),
                null, ethPkt, egressPort, Platform.currentTime + timeout)
    }
}
