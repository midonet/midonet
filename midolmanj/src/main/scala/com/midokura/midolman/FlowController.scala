// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor.{ActorRef, Actor}
import collection.JavaConversions._
import collection.mutable.{HashMap, MultiMap, Set}
import guice.ComponentInjectorHolder
import java.util.UUID

import com.midokura.sdn.dp.{Flow => KernelFlow, FlowMatch => KernelMatch, Datapath, Packet}

import com.midokura.sdn.flows.{NetlinkFlowTable, WildcardFlow,
WildcardFlowTable}
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

    // Callback argument should not block.
    case class RegisterPacketInListener(callback: (Packet, UUID) => Unit)
}

class FlowController extends Actor {

    import FlowController._
    import context._

    val log = Logging(context.system, this)

    private val pendedMatches: MultiMap[KernelMatch, Packet] =
        new HashMap[KernelMatch, Set[Packet]] with MultiMap[KernelMatch, Packet]

    @Inject
    var datapathConnection: OvsDatapathConnection = null

    @Inject
    var wildcardFlowManager: WildcardFlowTable = null

    @Inject
    var exactFlowManager: NetlinkFlowTable = null

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
                val kernelFlow = new KernelFlow()
                kernelFlow.setMatch(packet.getMatch)
                kernelFlow.setActions(wildcardFlow.actions)
                // XXX
                // TODO: installFlow(kernelFlow)
                // Send pended packets out the new rule
                if (pendedPackets != None)
                    for (unpendedPacket <- pendedPackets.get) {
                        // XXX
                        // TODO: packetOut(unpendedPacket, kernelFlow)
                    }
            }
            val evictedWcFlows = wildcardFlowManager.add(wildcardFlow)
            val evictedKernelFlows = (
                (Set[KernelFlow]() /: evictedWcFlows)
                    (_ ++ exactFlowManager.removeByWildcard(_)))
            for (kernelFlow <- evictedKernelFlows) {
                // XXX
                // TODO: removeFlow(kernelFlow.getMatch)
            }

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
        // First check if packet matches an exact flow, in case
        // the PacketIn notify crossed the flow's install message.
        val exactFlow = exactFlowManager.get(packet)
        if (exactFlow != null) {
            // XXX
            // TODO: packetOut(packet, exactFlow)
            return
        }

        // Query the WildcardFlowTable
        val wFlow = wildcardFlowManager.matchPacket(packet)
        if (wFlow != null) {
            val kFlow = new KernelFlow()
                .setMatch(packet.getMatch)
                .setActions(wFlow.actions)

            val evictedFlows = exactFlowManager.add(wFlow, kFlow)
            // XXX
            // TODO: installFlow(kFlow, packet)
            wildcardFlowManager.markUnused(evictedFlows.fst)
            for (kernelFlow <- evictedFlows.snd) {
                // XXX
                // TODO: removeFlow(kernelFlow)
            }
        } else {
            val kernelMatch = packet.getMatch
            if (pendedMatches.get(kernelMatch) == None) {
                datapathController() ! DatapathController.PacketIn(packet)
            }

            pendedMatches.addBinding(kernelMatch, packet)
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
