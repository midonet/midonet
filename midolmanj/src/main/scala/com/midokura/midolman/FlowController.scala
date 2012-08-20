// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor.{ActorRef, Actor}
import collection.JavaConversions._
import collection.mutable.{HashMap, MultiMap, Set}
import guice.ComponentInjectorHolder
import java.util.UUID

import com.midokura.sdn.dp.{FlowMatch, Flow, Datapath, Packet}

import com.midokura.sdn.flows.{FlowManager, WildcardFlow}
import com.midokura.midolman.openflow.MidoMatch
import com.midokura.sdn.dp.flows.FlowAction
import javax.inject.Inject
import com.midokura.netlink.protos.OvsDatapathConnection
import com.midokura.netlink.Callback
import com.midokura.netlink.exceptions.NetlinkException
import akka.event.Logging

object FlowController {
    val Name = "FlowController"

    case class AddWildcardFlow(wFlow: WildcardFlow, outPorts: Set[UUID],
                               packet: Option[Packet])

    case class RemoveWildcardFlow(fmatch: MidoMatch)

    case class SendPacket(data: Array[Byte], actions: List[FlowAction[_]],
                          outPorts: Set[UUID])

    case class Consume(packet: Packet)
}

class FlowController extends Actor {

    import FlowController._
    import context._

    val log = Logging(context.system, this)
    val maxDpFlows = 0

    private val pendedMatches: MultiMap[FlowMatch, Packet] =
        new HashMap[FlowMatch, Set[Packet]] with MultiMap[FlowMatch, Packet]

    @Inject
    var datapathConnection: OvsDatapathConnection = null

    @Inject
    var flowManager: FlowManager = null

    def datapathController(): ActorRef = {
        actorFor("/user/%s" format DatapathController.Name)
    }

    def receive = {
        case DatapathController.DatapathReady(datapath) =>
            installPacketInHook(datapath)

        case packetIn(packet) =>
            handlePacketIn(packet)

        case AddWildcardFlow(wildcardFlow, outPorts, packetOption) =>
            // TODO(pino, jlm): translate the outPorts to output actions and
            // TODO:            append them to the wildcardFlow's action list.
            // XXX
            if (packetOption != None) {
                val packet = packetOption.get
                val kernelMatch = packet.getMatch
                val pendedPackets = pendedMatches.remove(kernelMatch)
                val kernelFlow = new Flow()
                kernelFlow.setMatch(packet.getMatch)
                kernelFlow.setActions(wildcardFlow.getActions)
                // XXX
                // TODO: installFlow(kernelFlow)
                // Send pended packets out the new rule
                if (pendedPackets != None)
                    for (unpendedPacket <- pendedPackets.get) {
                        // XXX
                        // TODO: packetOut(unpendedPacket, kernelFlow)
                    }
            }
            // TODO(pino): is the datapath flow table reaching its limit?
            flowManager.add(wildcardFlow)
            /*val evictedKernelFlows = (
                (Set[Flow]() /: evictedWcFlows)
                    (_ ++ exactFlowManager.removeByWildcard(_)))
            for (kernelFlow <- evictedKernelFlows) {
                // XXX
                // TODO: removeFlow(kernelFlow.getMatch)
            }*/

        case Consume(packet) =>
            val kernelMatch = packet.getMatch
            pendedMatches.remove(kernelMatch)

        case RemoveWildcardFlow(fmatch) => //XXX

        case SendPacket(data, actions, outPorts) => //XXX
    }

    /**
     * Internal message posted by the netlink callback hook when a new packet not
     * matching any flows appears on one of the datapath ports.
     *
     * @param packet the packet data
     */
    case class packetIn(packet: Packet)

    private def handlePacketIn(packet: Packet) {
        // In case the PacketIn notify raced a flow rule installation, see if
        // the flowManager already has a match.
        val actions = flowManager.getActionsForDpFlow(packet.getMatch)
        if (actions != null) {
            // XXX TODO: packetOut(packet, exactFlow)
            return
        }
        // Otherwise, try to create a datapath flow based on an existing
        // wildcard flow.
        val dpFlow = flowManager.createDpFlow(packet.getMatch)
        if (dpFlow != null) {
            // Check whether some existing datapath flows will need to be
            // evicted to make space for the new one.
            if (flowManager.getNumDpFlows > maxDpFlows) {
                // Evict 1000 datapath flows.
                for (dpFlow <- flowManager.removeOldestDpFlows(1000)) {
                    // XXX TODO: remove each flow via the Netlink API
                }
            }
            // XXX TODO: installFlow(kFlow, packet)
            return
        }
        else {
            // Otherwise, pass the packetIn up to the next layer for handling.
            // Keep track of these packets so that for every FlowMatch, only
            // one such call goes to the next layer.
            if (pendedMatches.get(packet.getMatch) == None) {
                datapathController() ! DatapathController.PacketIn(packet)
            }
            pendedMatches.addBinding(packet.getMatch, packet)
        }
    }

    private def installPacketInHook(datapath: Datapath) {
        log.info("Installing packet in handler")
        // TODO: try to make this cleaner (right now we are just waiting for
        // the install future thus blocking the current thread).
        datapathConnection.datapathsSetNotificationHandler(datapath, new Callback[Packet] {
            def onSuccess(data: Packet) {
                self ! packetIn(data)
            }

            def onTimeout() {}

            def onError(e: NetlinkException) {}
        }).get()
    }
}
