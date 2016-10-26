/*
 * Copyright 2016 Midokura SARL
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
import java.util
import java.util.{ArrayList, Arrays, UUID}
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConversions._

import org.slf4j.LoggerFactory

import org.midonet.midolman._
import org.midonet.midolman.flows.ManagedFlow
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.PacketWorkflow.{GeneratedLogicalPacket, GeneratedPhysicalPacket}
import org.midonet.midolman.state.{ArpRequestBroker, FlowStateAgentPackets => FlowStatePackets}
import org.midonet.midolman.rules.RuleResult
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKeys}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger.{FlowStateTag, FlowTag}
import org.midonet.sdn.flows.VirtualActions.{Decap, Encap}
import org.midonet.util.Clearable
import org.midonet.util.collection.ArrayListUtil
import org.midonet.util.functors.Callback0
import org.midonet.util.logging.Logger

object PacketContext {
    val defaultLog =
        Logger(LoggerFactory.getLogger("org.midonet.packets.default.packet-processor"))
    val debugLog =
        Logger(LoggerFactory.getLogger("org.midonet.packets.debug.packet-processor"))
    val traceLog =
        Logger(LoggerFactory.getLogger("org.midonet.packets.trace.packet-processor"))

    /*
     * The default values doesn't work when called from java.
     * Workaround it by overloading the method.
     */
    def generatedForJava(cookie: Long, packet: Packet,
                         origMatch: FlowMatch,
                         egressPort: UUID): PacketContext =
        generated(cookie, packet, origMatch, egressPort)

    def generated(cookie: Long, packet: Packet,
                  origMatch: FlowMatch,
                  egressPort: UUID = null,
                  egressPortNo: JInteger = null,
                  backChannel: SimulationBackChannel = null,
                  arpBroker: ArpRequestBroker = null): PacketContext = {
        val context = new PacketContext()
        context.prepare(cookie, packet, origMatch, egressPort, egressPortNo,
                        backChannel, arpBroker)
        context
    }
}

/**
 * Part of the PacketContext, contains flow related fields that are commonly
 * accessed together, so that they are also grouped together when laid out
 * in memory.
 */
trait FlowContext extends Clearable { this: PacketContext =>
    // The virtual flow actions populated by the simulation
    val virtualFlowActions = new ArrayList[FlowAction]()

    // The translated list of flow actions to apply to the simulated packet.
    // If the packet is encap'ed, these apply to the inner packet.
    val packetActions = new ArrayList[FlowAction]()
    // The translated list of flow actions to apply to subsequent packets
    // (before (de)encapsulation if the packet is (de)encap'ed)
    val recircFlowActions = new ArrayList[FlowAction]()
    // The translated list of flow actions to apply to subsequent packets
    // (after (de)encapsulation if the packet is (de)encap'ed).
    val flowActions = new ArrayList[FlowAction]()

    // This Set stores the tags by which the flow may be indexed.
    // The index can be used to remove flows associated with the given tag.
    val flowTags = new ArrayList[FlowTag]()

    // The original packet's flow match, which is either the inner packet if
    // encap'ing or the outer if decap'ing. Taken from a Stash.
    var recircMatch: FlowMatch = _
    var recircPayload: Ethernet = _

    var flow: ManagedFlow = _

    def isRecirc: Boolean = recircPayload ne null

    def isDrop: Boolean = flowActions.isEmpty

    def isEncapped = isRecirc && (recircPayload.getParent ne null)

    def resetFlowContext(): Unit = {
        virtualFlowActions.clear()
        packetActions.clear()
        recircFlowActions.clear()
        flowActions.clear()
        flowTags.clear()
        recircMatch = null
        recircPayload = null
        flow = null
    }

    override def clear(): Unit = {
        if (recircMatch ne null)
            origMatch.reset(recircMatch)
        resetFlowContext()
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

    def encap(vni: Int, srcMac: MAC, dstMac: MAC,
              srcIp: IPv4Addr, dstIp: IPv4Addr,
              tos: Byte, ttl: Byte,
              srcPort: Int, dstPort: Int): Unit = {
        if (isRecirc)
            throw new UnsupportedOperationException("Cannot add 2 layers of encapsulation")

        calculateActionsFromMatchDiff()
        virtualFlowActions.add(Encap(vni))
        if (recircMatch eq null)
            recircMatch = new FlowMatch()
        recircMatch.reset(origMatch)
        recircMatch.allFieldsSeen()
        recircPayload = packet.getEthernet
        val outer: Ethernet = { eth src srcMac dst dstMac } <<
                              { ip4 src srcIp dst dstIp ttl ttl diff_serv tos } <<
                              { udp src srcPort.toShort dst dstPort.toShort } <<
                              { vxlan vni vni setPayload recircPayload }
        packet.setEthernet(outer)
        recircPayload.setParent(outer)
        // Forget original flow keys, and reset the original match to
        // the encapsulation headers
        origMatch.clear()
        val keys = FlowKeys.fromEthernetPacket(outer)
        origMatch.addKeys(keys)
        wcmatch.reset(origMatch)
        diffBaseMatch.reset(origMatch)
    }

    def decap(inner: Ethernet, vni: Int): Unit = {
        if (isRecirc)
            throw new UnsupportedOperationException("Cannot decap another layer of encapsulation")

        // There's no point in calculating actions: outer packet will be discarded
        virtualFlowActions.clear()
        virtualFlowActions.add(Decap(vni))
        if (recircMatch eq null)
            recircMatch = new FlowMatch()
        recircMatch.reset(origMatch)
        recircMatch.allFieldsSeen()

        recircPayload = packet.getEthernet
        packet.setEthernet(inner)

        // Reset the original match to the inner packet headers
        origMatch.clear()
        val keys = FlowKeys.fromEthernetPacket(inner)
        origMatch.addKeys(keys)
        wcmatch.reset(origMatch)
        diffBaseMatch.reset(origMatch)
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
            diffBaseMatch.getNetworkTTL != wcmatch.getNetworkTTL ||
            diffBaseMatch.getNetworkTOS != wcmatch.getNetworkTOS) {

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
    val traversedRulesMatched = new ArrayList[Boolean]
    val traversedRulesApplied = new ArrayList[Boolean]

    def recordTraversedRule(rule: UUID, result: RuleResult)
    : Unit = {
        traversedRules.add(rule)
        traversedRuleResults.add(result)
    }

    def recordMatchedRule(rule: UUID, matched: Boolean): Unit = {
        traversedRulesMatched.add(matched)
    }

    def recordAppliedRule(rule: UUID, applied: Boolean): Unit = {
        traversedRulesApplied.add(applied)
    }

    def resetRecordedContext(): Unit = {
        traversedRules.clear()
        traversedRuleResults.clear()
        traversedRulesMatched.clear()
        traversedRulesApplied.clear()
    }

    override def clear(): Unit = {
        resetRecordedContext()
        super.clear()
    }
}

trait RedirectContext extends Clearable {
    var redirectedOut = false
    var redirectFailOpen = false

    val servicePorts = new ArrayList[UUID]()

    def isRedirectedOut: Boolean = redirectedOut
    def isRedirectFailOpen: Boolean = redirectFailOpen

    def redirectOut(failOpen: Boolean): Unit = {
        redirectedOut = true
        redirectFailOpen = failOpen
    }

    def clearRedirect(): Unit = {
        redirectedOut = false
        redirectFailOpen = false
    }

    def resetRedirectContext(): Unit = {
        clearRedirect()
        servicePorts.clear()
    }

    override def clear(): Unit = {
        resetRedirectContext()
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
class PacketContext extends Clearable
        with FlowContext
        with RedirectContext
        with RecordedContext
        with StateContext {
    var log = PacketContext.defaultLog

    def jlog = log.underlying

    var portGroups: util.List[UUID] = null
    var inPortGroups: util.List[UUID] = null
    var outPortGroups: util.List[UUID] = null

    var idle: Boolean = true
    var runs: Int = 0

    var devicesTraversed = 0

    var inPortId: UUID = _
    var outPortId: UUID = _
    val outPorts = new ArrayList[UUID]()
    var currentDevice: UUID = _
    var routeTo: Route = _
    var nwDstRewritten: Boolean = _

    val preRoutingMatch = new FlowMatch
    val wcmatch = new FlowMatch
    val diffBaseMatch = new FlowMatch

    var inputPort: UUID = _

    var backChannel: SimulationBackChannel = _
    var arpBroker: ArpRequestBroker = _

    var cookie: Long = -1
    var packet: Packet = null
    val origMatch: FlowMatch = new FlowMatch
    var egressPort: UUID = null
    var egressPortNo: JInteger = null

    val flowProcessed: AtomicBoolean = new AtomicBoolean(false)
    val packetProcessed: AtomicBoolean = new AtomicBoolean(false)

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

    def prepare(cookie: Long,
                packet: Packet,
                origMatch: FlowMatch,
                egressPort: UUID, egressPortNo: JInteger,
                backChannel: SimulationBackChannel,
                arpBroker: ArpRequestBroker): Unit = {
        resetContext()
        this.cookie = cookie
        this.packet = packet

        this.origMatch.reset(origMatch)
        this.preRoutingMatch.reset(origMatch)
        this.wcmatch.reset(origMatch)
        this.diffBaseMatch.reset(origMatch)

        this.egressPort = egressPort
        this.egressPortNo = egressPortNo

        this.backChannel = backChannel
        this.arpBroker = arpBroker
    }

    def resetContext(): Unit = {
        flowProcessed.set(false)
        packetProcessed.set(false)

        resetFlowContext()
        resetRedirectContext()
        resetRecordedContext()
        resetStateContext()

        this.log = PacketContext.defaultLog
        this.idle = true
        this.devicesTraversed = 0
        this.runs = 0
        this.cookie = -1
        this.packet = null
        this.origMatch.clear()
        this.egressPort = null
        this.egressPortNo = null
        this.preRoutingMatch.clear()
        this.wcmatch.clear()
        this.diffBaseMatch.clear()
        this.inPortId = null
        this.outPortId = null
        this.outPorts.clear()
        this.portGroups = null
        this.inPortGroups = null
        this.outPortGroups = null
        this.flowRemovedCallbacks.clear()
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
        origMatch.clearSeenFields()
        wcmatch.reset(origMatch)
        diffBaseMatch.reset(origMatch)
        preRoutingMatch.reset(origMatch)
    }

    def prepareForDrop(): Unit = {
        idle = false
        clear()
    }

    def postpone() {
        // reset the payload and its original flow match in case it was
        // encapsulated or decapsulated during this try of the simulation.
        if (recircPayload ne null)
            packet.setEthernet(recircPayload)
        idle = true
        inputPort = null
        clear()
    }

    def isProcessed = flowProcessed.get && packetProcessed.get
    def setFlowProcessed(): Unit = flowProcessed.set(true)
    def setPacketProcessed(): Unit = packetProcessed.set(true)

    def addGeneratedPacket(uuid: UUID, ethernet: Ethernet): Unit =
        backChannel.tell(GeneratedLogicalPacket(uuid, ethernet, cookie))

    def addGeneratedPhysicalPacket(portNo: JInteger,
                                   ethernet: Ethernet): Unit =
        backChannel.tell(GeneratedPhysicalPacket(portNo, ethernet, cookie))

    def markUserspaceOnly(): Unit =
        wcmatch.markUserspaceOnly()

    def markNwDstRewritten(): Unit = nwDstRewritten = true

    override def toString = s"PacketContext($cookieStr tags$flowTags actions$virtualFlowActions $origMatch)"
}
