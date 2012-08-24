// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor.{ActorRef, Actor}

import openflow.MidoMatch
import com.midokura.sdn.flows.{WildcardMatch, WildcardFlow}
import java.util.UUID
import com.midokura.packets.Ethernet
import collection.mutable
import com.midokura.sdn.dp.Packet
import com.midokura.sdn.dp.flows.FlowAction
import com.midokura.midolman.FlowController.{RemoveWildcardFlow, AddWildcardFlow, Drop, SendPacket}
import akka.event.Logging
import com.midokura.midolman.DatapathController.{DeleteFlow, PacketIn}
import com.sun.xml.internal.ws.api.streaming.XMLStreamReaderFactory.Woodstox

object SimulationController {

    val Name = "SimulationController"

    case class SimulationDone(origMatch: WildcardMatch, packet: Array[Byte])

    case class EmitGeneratedPacket(vportID: UUID, frame: Ethernet)
}

class SimulationController() extends Actor {
    import SimulationController._
    import context._

    val log = Logging(context.system, this)

    protected def fController():ActorRef = {
        actorFor("/user/%s" format FlowController.Name)
    }

    protected def datapathController():ActorRef = {
        actorFor("/user/%s" format DatapathController.Name)
    }

    def receive = {

        case sim: SimulationDone =>
            // when it gets this message it can generated one or more of the following messages to the datapathContoller or to self.
            // datapathController ! AddWildcardFlow(...)
            // datapathController ! SendPacket(...)
            // self ! PacketIn()

            // if (connectionTracked)
            //  ClusterClient.installConnectionTrackingBlobs();

            // Emtpy or null outPorts indicates Drop rule.
            // Null finalMatch indicates that the packet was consumed.
            /*if (generated) {
                if (null != finalMatch) {
                    // TODO(pino, jlm): diff matches to build action list
                    // XXX
                    val actions: List[FlowAction[_]] = null
                    datapathController() ! SendPacket(packet.getData, actions)
                }
                // Else, do nothing, the packet is dropped.
            } else if (finalMatch == null) {
                fController ! Drop(packet)
            } else {
                // TODO(pino, jlm): compute the WildcardFlow, including actions
                // XXX
                val wildcardFlow: WildcardFlow = null
                datapathController ! AddWildcardFlow(wildcardFlow, Some(packet), null, null)
            } */

        case EmitGeneratedPacket(vportID, frame) =>
            // TODO(pino, jlm): do a new simulation.
            // XXX
            // self ! PacketIn(new Packet())
    }
}
