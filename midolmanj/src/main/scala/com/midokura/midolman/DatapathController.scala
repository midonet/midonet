// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import akka.actor.Actor
import collection.JavaConversions._
import collection.mutable.{HashMap, MultiMap, Set}

import com.midokura.util.netlink.dp.{Flow => KernelFlow, FlowMatch => KernelMatch, Packet}
import com.midokura.sdn.flows.{WildcardFlow, NetlinkFlowTable, WildcardFlowTable}
import openflow.MidoMatch
import java.util.UUID
import com.midokura.util.netlink.dp.flows.FlowAction

case class AddWildcardFlow(wFlow: WildcardFlow, outPorts: Set[UUID],
                           packet: Option[Packet])
case class RemoveWildcardFlow(fmatch: MidoMatch)
case class SendPacket(data: Array[Byte], actions: List[FlowAction[_]],
                      outPorts: Set[UUID])
case class Consume(packet: Packet)
// Callback argument should not block.
case class RegisterPacketInListener(callback: (Packet, UUID) => Unit)

class DatapathController(XXX: Unit) extends Actor {
    private var wildcardFlowManager: WildcardFlowTable = _
    private var exactFlowManager: NetlinkFlowTable = _
    private var packetInCallback: (Packet, UUID) => Unit = null
    private val pendedMatches: MultiMap[KernelMatch, Packet] =
        new HashMap[KernelMatch, Set[Packet]] with MultiMap[KernelMatch, Packet]


    // Send this message to myself when I get the NL packetIn callback
    case class PacketIn(packet: Packet)
    // Callback invoked from select-loop thread context.
    // TODO(pino, jlm): register this callback
    // XXX
    def onPacketIn(packet: Packet) {
        self ! PacketIn(packet)
    }

    private def doPacketIn(packet: Packet) {
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
            val kFlow = new KernelFlow().
                setMatch(packet.getMatch).setActions(wFlow.actions)
            val evictedFlows = exactFlowManager.add(wFlow, kFlow)
            // XXX
            // TODO: installFlow(kFlow, packet)
            wildcardFlowManager.markUnused(evictedFlows.fst)
            for (kernelFlow <- evictedFlows.snd) {
                // XXX
                // TODO: removeFlow(kernelFlow)
            }
        } else if(packetInCallback != null) {
            val kernelMatch = packet.getMatch
            if (pendedMatches.get(kernelMatch) == None) {
                // XXX
                // TODO: translate the Packet's inPort to a UUID
                val inPortID: UUID = null
                packetInCallback(packet, inPortID)
            }
            pendedMatches.addBinding(kernelMatch, packet)
        }
    }

    def receive = {
        case PacketIn(packet) => doPacketIn(packet)

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

        case RemoveWildcardFlow(fmatch) =>
        case SendPacket(data, actions, outPorts) =>
        case RegisterPacketInListener(callback) =>
            packetInCallback = callback

    }

}
