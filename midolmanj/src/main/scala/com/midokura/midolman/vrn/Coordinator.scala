// Copyright 2012 Midokura Inc.

package com.midokura.midolman.vrn

import akka.actor.Actor
import java.util.UUID
import com.midokura.midolman.openflow.MidoMatch


class PortMatch(var port: UUID, var mmatch: MidoMatch) extends Cloneable { 
    override def clone = {
        new PortMatch(port, mmatch)
    }
}

case class SimulatePacket(ingress: PortMatch)
case class SimulationResult(egress: PortMatch)

class Coordinator extends Actor {
    def doProcess(ingress: PortMatch): PortMatch = {
        var currentPortMatch = ingress.clone
        while (true) {
            // TODO(jlm): Check for too long loop
            val currentFE = deviceOfPort(currentPortMatch.port)
            val nextPortMatch = currentFE.process(currentPortMatch)
            currentPortMatch.mmatch = nextPortMatch.mmatch
            val peerPort = peerOfPort(nextPortMatch.port)
            if (peerPort == null)
                return currentPortMatch
            currentPortMatch.port = peerPort
        }
        return null
    }

    private def deviceOfPort(port: UUID): Device = {
        null   //XXX
    }

    private def peerOfPort(port: UUID): UUID = {
        null   //XXX
    }

    def receive = {
        case SimulatePacket(portmatch) => 
                sender ! SimulationResult(doProcess(portmatch))
    }
}
