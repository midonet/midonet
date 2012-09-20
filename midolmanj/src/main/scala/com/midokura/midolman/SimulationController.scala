// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import compat.Platform
import akka.actor.{Actor, ActorLogging}
import akka.util.duration._
import java.util.UUID

import com.midokura.cache.Cache
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
    val connectionCache: Cache = null  //XXX

    def receive = {
        case PacketIn(wMatch, pktBytes, _, _, cookie) =>
            new Coordinator(
                wMatch, Ethernet.deserialize(pktBytes), cookie, None,
                Platform.currentTime + timeout, connectionCache).simulate

        case EmitGeneratedPacket(egressPort, ethPkt) =>
            new Coordinator(
                WildcardMatches.fromEthernetPacket(ethPkt), ethPkt, None,
                Some(egressPort), Platform.currentTime + timeout,
                connectionCache).simulate
    }
}
