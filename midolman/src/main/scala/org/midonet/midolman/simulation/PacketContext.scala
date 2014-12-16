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

import java.util.{ArrayList, Arrays, UUID, Set => JSet}

import scala.collection.JavaConversions._
import scala.collection.mutable

import akka.actor.ActorSystem
import com.typesafe.scalalogging.Logger
import org.midonet.midolman.state.FlowStatePackets
import org.midonet.odp.Packet
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKeys}
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.{FlowStateTag, FlowTag}
import org.midonet.sdn.flows.VirtualActions.VirtualFlowAction
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.functors.Callback0
import org.slf4j.LoggerFactory

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
trait FlowContext { this: PacketContext =>
    val virtualFlowActions = new ArrayList[FlowAction]()
    val flowActions = new ArrayList[FlowAction]()
    // This Set stores the tags by which the flow may be indexed.
    // The index can be used to remove flows associated with the given tag.
    val flowTags = mutable.Set[FlowTag]()
    var hardExpirationMillis = 0
    var idleExpirationMillis = 0

    def isDrop: Boolean = flowActions.isEmpty

    def clear(): Unit = {
        virtualFlowActions.clear()
        flowActions.clear()
        flowTags.clear()
        hardExpirationMillis = 0
        idleExpirationMillis = 0
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

    def addFlowAction(action: FlowAction): Unit =
        flowActions.add(action)

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

    /*
     * Compares two objects, which may be null, to determine if they should
     * cause flow actions.
     * The catch here is that if `modif` is null, the verdict is true regardless
     * because we don't create actions that set values to null.
     */
    private def matchObjectsSame(orig: AnyRef, modif: AnyRef) =
        (modif eq null) || orig == modif

    private def diffEthernet(): Unit =
        if (!origMatch.getEthSrc.equals(wcmatch.getEthSrc) ||
            !origMatch.getEthDst.equals(wcmatch.getEthDst)) {
            virtualFlowActions.add(setKey(FlowKeys.ethernet(
                wcmatch.getEthSrc.getAddress,
                wcmatch.getEthDst.getAddress)))
        }

    private def diffIp(): Unit =
        if (!matchObjectsSame(origMatch.getNetworkSrcIP,
                              wcmatch.getNetworkSrcIP) ||
            !matchObjectsSame(origMatch.getNetworkDstIP,
                              wcmatch.getNetworkDstIP) ||
            !matchObjectsSame(origMatch.getNetworkTTL,
                              wcmatch.getNetworkTTL)) {
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
        if (!matchObjectsSame(origMatch.getSrcPort, wcmatch.getSrcPort) ||
            !matchObjectsSame(origMatch.getDstPort, wcmatch.getDstPort)) {

            wcmatch.getNetworkProto.byteValue() match {
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
class PacketContext(val cookieOrEgressPort: Either[Int, UUID],
                    val packet: Packet,
                    val parentCookie: Option[Int],
                    val origMatch: WildcardMatch)
                   (implicit actorSystem: ActorSystem) extends FlowContext {
    var log = PacketContext.defaultLog

    def jlog = log.underlying

    val state = new StateContext(this, log)
    var portGroups: JSet[UUID] = null

    var lastInvalidation: Long = _

    var idle: Boolean = true
    var runs: Int = 0

    var inPortId: UUID = _
    var outPortId: UUID = _
    val outPorts = new ArrayList[UUID]()

    val wcmatch = origMatch.clone()

    private var traceID: UUID = null
    private var traceStep = 0
    private var isTraced = false

    var inputPort: UUID = _

    // Stores the callback to call when this flow is removed.
    val flowRemovedCallbacks = new ArrayList[Callback0]()
    def addFlowRemovedCallback(cb: Callback0): Unit = {
        flowRemovedCallbacks.add(cb)
    }

    def runFlowRemovedCallbacks(): Unit = {
        var i = 0
        while (i < flowRemovedCallbacks.size()) {
            flowRemovedCallbacks.get(i).call()
            i += 1
        }
        flowRemovedCallbacks.clear()
    }

    def ethernet = packet.getEthernet

    def isGenerated = cookieOrEgressPort.isRight
    def ingressed = cookieOrEgressPort.isLeft
    def isStateMessage = origMatch.getTunnelKey == FlowStatePackets.TUNNEL_KEY

    def flowCookie = cookieOrEgressPort.left.toOption

    def cookieStr = (if (isGenerated) "[genPkt:" else "[cookie:") +
                    flowCookie.getOrElse(parentCookie.getOrElse("No Cookie")) + "]"

    def prepareForSimulation(lastInvalidationSeen: Long) {
        idle = false
        runs += 1
        lastInvalidation = lastInvalidationSeen
    }

    def prepareForDrop(lastInvalidationSeen: Long) {
        idle = false
        lastInvalidation = lastInvalidationSeen
        clear()
        state.clear()
        runFlowRemovedCallbacks()
    }

    def postpone() {
        idle = true
        clear()
        state.clear()
        runFlowRemovedCallbacks()
        wcmatch.reset(origMatch)
        inputPort = null
    }

    override def toString = s"PacketContext[$cookieStr]"
}
