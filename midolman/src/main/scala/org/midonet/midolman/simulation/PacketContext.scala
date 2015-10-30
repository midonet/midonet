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

import java.lang.{Integer => JInteger}
import java.util.{Arrays, ArrayList, HashSet, Set => JSet, UUID}
import org.midonet.midolman.layer3.Route

import scala.collection.JavaConversions._

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.midolman._
import org.midonet.midolman.flows.ManagedFlow
import org.midonet.midolman.simulation.PacketEmitter.GeneratedLogicalPacket
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPhysicalPacket
import org.midonet.midolman.state.{ArpRequestBroker, FlowStatePackets}
import org.midonet.midolman.rules.RuleResult
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKeys}
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.{FlowStateTag, FlowTag}
import org.midonet.util.Clearable
import org.midonet.util.collection.ArrayListUtil
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
    val flowTags = new ArrayList[FlowTag]()

    var flow: ManagedFlow = _

    def isDrop: Boolean = flowActions.isEmpty

    override def clear(): Unit = {
        virtualFlowActions.clear()
        flowActions.clear()
        packetActions.clear()
        flowTags.clear()
        super.clear()
    }

    def addFlowTag(tag: FlowTag): Unit = {
        flowTags.add(tag)
    }

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
        diffEthernet()
        diffIp()
        diffVlan()
        diffIcmp()
        diffL4()
        wcmatch.doTrackSeenFields()
        diffBaseMatch.reset(wcmatch)
        diffBaseMatch.doTrackSeenFields()
    }

    private def diffEthernet(): Unit =
        if (!diffBaseMatch.getEthSrc.equals(wcmatch.getEthSrc) ||
            !diffBaseMatch.getEthDst.equals(wcmatch.getEthDst)) {
            virtualFlowActions.add(setKey(FlowKeys.ethernet(
                wcmatch.getEthSrc.getAddress,
                wcmatch.getEthDst.getAddress)))
        }

    private def diffIp(): Unit =
        if (diffBaseMatch.getNetworkSrcIP != wcmatch.getNetworkSrcIP ||
            diffBaseMatch.getNetworkDstIP != wcmatch.getNetworkDstIP ||
            diffBaseMatch.getNetworkTTL != wcmatch.getNetworkTTL) {

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
        if (!ArrayListUtil.equals(diffBaseMatch.getVlanIds, wcmatch.getVlanIds)) {
            val vlansToRemove = diffBaseMatch.getVlanIds.diff(wcmatch.getVlanIds)
            val vlansToAdd = wcmatch.getVlanIds.diff(diffBaseMatch.getVlanIds)
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
            !Arrays.equals(icmpData, diffBaseMatch.getIcmpData)) {

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
        if (diffBaseMatch.getSrcPort != wcmatch.getSrcPort ||
            diffBaseMatch.getDstPort != wcmatch.getDstPort) {
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

trait RecordedContext extends Clearable {
    val traversedRules = new ArrayList[UUID]
    val traversedRuleResults = new ArrayList[RuleResult]

    def recordTraversedRule(rule: UUID, result: RuleResult): Unit = {
        traversedRules.add(rule)
        traversedRuleResults.add(result)
    }

    override def clear(): Unit = {
        traversedRules.clear()
        traversedRuleResults.clear()
        super.clear()
    }
}

trait RedirectContext extends Clearable {
    var redirectedOut = false
    var redirectFailOpen = false

    def isRedirectedOut(): Boolean = redirectedOut
    def isRedirectFailOpen(): Boolean = redirectFailOpen

    def redirectOut(failOpen: Boolean): Unit = {
        redirectedOut = true
        redirectFailOpen = failOpen
    }

    def clearRedirect(): Unit = {
        redirectedOut = false
        redirectFailOpen = false
    }

    override def clear(): Unit = {
        clearRedirect()
        super.clear()
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
                    val egressPort: UUID = null,
                    val egressPortNo: JInteger = null) extends Clearable
                                                 with FlowContext
                                                 with RedirectContext
                                                 with RecordedContext
                                                 with StateContext {
    var log = PacketContext.defaultLog

    def jlog = log.underlying

    var portGroups: ArrayList[UUID] = null

    var idle: Boolean = true
    var runs: Int = 0

    var devicesTraversed = 0

    var inPortId: UUID = _
    var outPortId: UUID = _
    val outPorts = new ArrayList[UUID]()
    var currentDevice: UUID = _
    var routeTo: Route = _

    val wcmatch = origMatch.clone()
    val diffBaseMatch = origMatch.clone()

    var inputPort: UUID = _

    var packetEmitter: PacketEmitter = _
    var arpBroker: ArpRequestBroker = _

    // Stores the callback to call when this flow is removed.
    val flowRemovedCallbacks = new ArrayList[Callback0]()
    def addFlowRemovedCallback(cb: Callback0): Unit = {
        flowRemovedCallbacks.add(cb)
    }

    def ethernet = packet.getEthernet

    def isGenerated = (egressPort ne null) || (egressPortNo ne null)
    def ingressed = !isGenerated
    def isStateMessage = origMatch.getTunnelKey == FlowStatePackets.TUNNEL_KEY

    def cookieStr = s"[cookie:$cookie]"

    def reset(packetEmitter: PacketEmitter, arpBroker: ArpRequestBroker): Unit = {
        this.packetEmitter = packetEmitter
        this.arpBroker = arpBroker
    }

    override def clear(): Unit = {
        super.clear()
        flowRemovedCallbacks.runAndClear()
    }

    def prepareForSimulation() {
        idle = false
        runs += 1
        devicesTraversed = 0
        currentDevice = null
        routeTo = null
        wcmatch.reset(origMatch)
        diffBaseMatch.reset(origMatch)
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
        packetEmitter.schedule(GeneratedLogicalPacket(uuid, ethernet))

    def addGeneratedPhysicalPacket(portNo: JInteger,
                                   ethernet: Ethernet): Unit =
        packetEmitter.schedule(GeneratedPhysicalPacket(portNo, ethernet))

    def markUserspaceOnly(): Unit =
        wcmatch.markUserspaceOnly()

    override def toString = s"PacketContext($cookieStr tags$flowTags actions$virtualFlowActions $origMatch)"

    /*
     * The default values doesn't work in case called from java.
     * Workaound it by overloading the constructor.
     */
    def this(cookie: Int, packet: Packet, origMatch: FlowMatch,
             egressPort: UUID) =
        this(cookie, packet, origMatch, egressPort, null)
}
