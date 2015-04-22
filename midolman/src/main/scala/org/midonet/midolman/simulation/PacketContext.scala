/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.simulation

import java.util.{Arrays, ArrayList, HashSet, Set => JSet, UUID}
import scala.collection.JavaConversions._

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.midolman._
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.state.{ArpRequestBroker, FlowStatePackets}
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKeys}
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.{FlowStateTag, FlowTag}
import org.midonet.util.Clearable
import org.midonet.util.functors.Callback0

object PacketContext {
    val defaultLog =
        Logger(LoggerFactory.getLogger("org.midonet.packets.default.packet-processor"))
    val debugLog =
        Logger(LoggerFactory.getLogger("org.midonet.packets.debug.packet-processor"))
    val traceLog =
        Logger(LoggerFactory.getLogger("org.midonet.packets.trace.packet-processor"))
}

/**
 * Part of the PacketContext, contains flow related fields that are commonly
 * accessed together, so that they are also grouped together when laid out
 * in memory.
 */
trait FlowContext extends Clearable { this: PacketContext =>
    val virtualFlowActions = new ArrayList[FlowAction]()
    val flowActions = new ArrayList[FlowAction]()
    val packetActions = new ArrayList[FlowAction]()
    // This Set stores the tags by which the flow may be indexed.
    // The index can be used to remove flows associated with the given tag.
    val flowTags = new HashSet[FlowTag]()

    def isDrop: Boolean = flowActions.isEmpty

    override def clear(): Unit = {
        virtualFlowActions.clear()
        flowActions.clear()
        flowTags.clear()
        super.clear()
    }

    def addFlowTag(tag: FlowTag): Unit =
        flowTags.add(tag)

    def clearFlowTags(): Unit = {
        val it = flowTags.iterator
        while (it.hasNext) {
            if (it.next().isInstanceOf[FlowStateTag]) {
                it.remove()
            }
        }
    }

    def addVirtualAction(action: FlowAction): Unit =
        virtualFlowActions.add(action)

    def addFlowAndPacketAction(action: FlowAction): Unit = {
        flowActions.add(action)
        packetActions.add(action)
    }

    def calculateActionsFromMatchDiff(): Unit = {
        wcmatch.doNotTrackSeenFields()
        origMatch.doNotTrackSeenFields()
        diffEthernet()
        diffIp()
        diffVlan()
        diffIcmp()
        diffL4()
        wcmatch.doTrackSeenFields()
        origMatch.doTrackSeenFields()
    }

    private def diffEthernet(): Unit =
        if (!origMatch.getEthSrc.equals(wcmatch.getEthSrc) ||
            !origMatch.getEthDst.equals(wcmatch.getEthDst)) {
            virtualFlowActions.add(setKey(FlowKeys.ethernet(
                wcmatch.getEthSrc.getAddress,
                wcmatch.getEthDst.getAddress)))
        }

    private def diffIp(): Unit =
        if (origMatch.getNetworkSrcIP != wcmatch.getNetworkSrcIP ||
            origMatch.getNetworkDstIP != wcmatch.getNetworkDstIP ||
            origMatch.getNetworkTTL != wcmatch.getNetworkTTL) {

            virtualFlowActions.add(setKey(
                wcmatch.getNetworkSrcIP match {
                    case srcIP: IPv4Addr =>
                        FlowKeys.ipv4(srcIP,
                            wcmatch.getNetworkDstIP.asInstanceOf[IPv4Addr],
                            wcmatch.getNetworkProto,
                            wcmatch.getNetworkTOS,
                            wcmatch.getNetworkTTL,
                            wcmatch.getIpFragmentType)
                    case srcIP: IPv6Addr =>
                        FlowKeys.ipv6(srcIP,
                            wcmatch.getNetworkDstIP.asInstanceOf[IPv6Addr],
                            wcmatch.getNetworkProto,
                            wcmatch.getNetworkTTL,
                            wcmatch.getIpFragmentType)
                }
            ))
        }

    private def diffVlan(): Unit =
        if (!origMatch.getVlanIds.equals(wcmatch.getVlanIds)) {
            val vlansToRemove = origMatch.getVlanIds.diff(wcmatch.getVlanIds)
            val vlansToAdd = wcmatch.getVlanIds.diff(origMatch.getVlanIds)
            log.debug("Vlan tags to pop {}, vlan tags to push {}",
                vlansToRemove, vlansToAdd)

            for (vlan <- vlansToRemove) {
                virtualFlowActions.add(popVLAN())
            }

            var count = vlansToAdd.size
            for (vlan <- vlansToAdd) {
                count -= 1
                val protocol = if (count == 0) Ethernet.VLAN_TAGGED_FRAME
                else Ethernet.PROVIDER_BRIDGING_TAG
                virtualFlowActions.add(FlowActions.pushVLAN(vlan, protocol))
            }
        }

    private def diffIcmp(): Unit = {
        val icmpData = wcmatch.getIcmpData
        if ((icmpData ne null) &&
            !Arrays.equals(icmpData, origMatch.getIcmpData)) {

            val icmpType = wcmatch.getSrcPort
            if (icmpType == ICMP.TYPE_PARAMETER_PROBLEM ||
                    icmpType == ICMP.TYPE_UNREACH ||
                    icmpType == ICMP.TYPE_TIME_EXCEEDED) {

                virtualFlowActions.add(setKey(FlowKeys.icmpError(
                    wcmatch.getSrcPort.byteValue(),
                    wcmatch.getDstPort.byteValue(),
                    wcmatch.getIcmpData
                )))
            }
        }
    }

    private def diffL4(): Unit =
        if (origMatch.getSrcPort != wcmatch.getSrcPort ||
            origMatch.getDstPort != wcmatch.getDstPort) {
            wcmatch.getNetworkProto match {
                case TCP.PROTOCOL_NUMBER =>
                    virtualFlowActions.add(setKey(FlowKeys.tcp(
                        wcmatch.getSrcPort,
                        wcmatch.getDstPort)))
                case UDP.PROTOCOL_NUMBER =>
                    virtualFlowActions.add(setKey(FlowKeys.udp(
                        wcmatch.getSrcPort,
                        wcmatch.getDstPort)))
                case ICMP.PROTOCOL_NUMBER =>
                // this case would only happen if icmp id in ECHO req/reply
                // were translated, which is not the case, so leave alone
            }
        }
}

/**
 * The PacketContext represents the simulation of a packet traversing the
 * virtual topology. Since a simulation runs-to-completion, always in the
 * context of the same thread, the PacketContext can be safely mutated and
 * used to pass state between different simulation stages, or between virtual
 * devices.
 */
class PacketContext(val cookie: Int,
                    val packet: Packet,
                    val origMatch: FlowMatch,
                    val egressPort: UUID = null) extends Clearable
                                                 with FlowContext
                                                 with StateContext {
    var log = PacketContext.defaultLog

    def jlog = log.underlying

    var portGroups: JSet[UUID] = null

    var idle: Boolean = true
    var runs: Int = 0

    var inPortId: UUID = _
    var outPortId: UUID = _
    val outPorts = new ArrayList[UUID]()

    val wcmatch = origMatch.clone()

    var inputPort: UUID = _

    var packetEmitter: PacketEmitter = _
    var arpBroker: ArpRequestBroker = _

    // Stores the callback to call when this flow is removed.
    val flowRemovedCallbacks = new ArrayList[Callback0]()
    def addFlowRemovedCallback(cb: Callback0): Unit = {
        flowRemovedCallbacks.add(cb)
    }

    def ethernet = packet.getEthernet

    def isGenerated = egressPort ne null
    def ingressed = egressPort eq null
    def isStateMessage = origMatch.getTunnelKey == FlowStatePackets.TUNNEL_KEY

    def cookieStr = s"[cookie:$cookie]"

    def reset(packetEmitter: PacketEmitter, arpBroker: ArpRequestBroker): Unit = {
        this.packetEmitter = packetEmitter
        this.arpBroker = arpBroker
    }

    override def clear(): Unit = {
        super.clear()
        flowRemovedCallbacks.runAndClear()
        wcmatch.reset(origMatch)
    }

    def prepareForSimulation() {
        idle = false
        runs += 1
    }

    def prepareForDrop(): Unit = {
        idle = false
        clear()
    }

    def postpone() {
        idle = true
        inputPort = null
        clear()
    }

    def addGeneratedPacket(uuid: UUID, ethernet: Ethernet): Unit =
        packetEmitter.schedule(GeneratedPacket(uuid, ethernet))

    override def toString = s"PacketContext[$cookieStr]"
}
