// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import akka.actor.Actor
import java.util.UUID
import com.midokura.midolman.openflow.MidoMatch
import com.midokura.packets.Ethernet


class PortMatch(var port: UUID, var mmatch: MidoMatch) extends Cloneable {
    override def clone = {
        new PortMatch(port, mmatch)
    }
}

class PacketContext(var port: UUID, var mmatch: MidoMatch,
                    val packet: Ethernet) {}

case class SimulatePacket(ingress: PacketContext)

case class SimulationResult(result: ProcessResult)

class Coordinator extends Actor {
    def doProcess(pktContext: PacketContext): ProcessResult = {
        while (true) {
            // TODO(jlm): Check for too long loop
            val currentFE = deviceOfPort(pktContext.port)
            val result = currentFE.process(pktContext, context.dispatcher)
            result match {
                case ForwardResult(nextPortMatch) =>
                    pktContext.mmatch = nextPortMatch.mmatch
                    val peerPort = peerOfPort(nextPortMatch.port)
                    if (peerPort == null)
                        return result
                    pktContext.port = peerPort
                case _ => return result
            }
        }
        return null
    }

    private def deviceOfPort(port: UUID): Device = {
        null //XXX
    }

    private def peerOfPort(port: UUID): UUID = {
        null //XXX
    }

    def receive = {
        case SimulatePacket(portmatch) =>
            sender ! SimulationResult(doProcess(portmatch))
    }
}
