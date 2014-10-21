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

import java.text.SimpleDateFormat
import java.util.{Arrays, ArrayList, Date, Set => JSet, UUID}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.actor.ActorSystem
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.midolman.state.FlowStatePackets
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.odp.flows.{FlowActions, FlowKeys, FlowAction}
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.Packet
import org.midonet.packets._
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.flows.FlowTagger.{FlowStateTag, FlowTag}
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
                   (implicit actorSystem: ActorSystem) {
    var log = PacketContext.defaultLog

    def jlog = log.underlying

    val state = new StateContext(this, log)
    var portGroups: JSet[UUID] = null

    var lastInvalidation: Long = _

    var idle: Boolean = true
    var runs: Int = 0

    var inPortId: UUID = _
    var outPortId: UUID = _
    var toPortSet: Boolean = _

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

    // This Set stores the tags by which the flow may be indexed.
    // The index can be used to remove flows associated with the given tag.
    var flowTags = mutable.Set[FlowTag]()

    def addFlowTag(tag: FlowTag): Unit =
        flowTags.add(tag)

    def clearFlowTags(): Unit =
        flowTags = flowTags filter (_.isInstanceOf[FlowStateTag])

    def prepareForSimulation(lastInvalidationSeen: Long) {
        idle = false
        runs += 1
        lastInvalidation = lastInvalidationSeen
    }

    def prepareForDrop(lastInvalidationSeen: Long) {
        idle = false
        lastInvalidation = lastInvalidationSeen
        flowTags.clear()
        state.clear()
        runFlowRemovedCallbacks()
    }

    def postpone() {
        idle = true
        flowTags.clear()
        state.clear()
        runFlowRemovedCallbacks()
        wcmatch.reset(origMatch)
        inputPort = null
    }

    var traceConditions: TraceConditions = null

    def setTraced(flag: Boolean) {
        if (!isTraced && flag) {
            traceID = UUID.randomUUID
        }
        isTraced = flag
    }

    def traceMessage(equipmentID: UUID, msg: String) {
        if (isTraced) {
            traceStep += 1
            val key: String = traceID.toString + ":" + traceStep
            val equipStr: String = if (equipmentID == null)
                                       "(none)"
                                   else
                                       equipmentID.toString
            val value = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ")
                            .format(new Date) + equipStr + " " + msg
            //traceMessageCache.set(key, value)
            //traceIndexCache.set(traceID.toString, traceStep.toString)
        }
    }

    def actionsFromMatchDiff(): ArrayBuffer[FlowAction] = {
        val actions = new ArrayBuffer[FlowAction]()
        wcmatch.doNotTrackSeenFields()
        origMatch.doNotTrackSeenFields()
        diffEthernet(actions)
        diffIp(actions)
        diffVlan(actions)
        diffIcmp(actions)
        diffL4(actions)
        wcmatch.doTrackSeenFields()
        origMatch.doTrackSeenFields()
        actions
    }

    /*
     * Compares two objects, which may be null, to determine if they should
     * cause flow actions.
     * The catch here is that if `modif` is null, the verdict is true regardless
     * because we don't create actions that set values to null.
     */
    private def matchObjectsSame(orig: AnyRef, modif: AnyRef) =
        (modif eq null) || orig == modif

    private def diffEthernet(actions: ArrayBuffer[FlowAction]): Unit =
        if (!origMatch.getEthSrc.equals(wcmatch.getEthSrc) ||
            !origMatch.getEthDst.equals(wcmatch.getEthDst)) {
            actions.append(setKey(FlowKeys.ethernet(
                wcmatch.getEthSrc.getAddress,
                wcmatch.getEthDst.getAddress)))
        }

    private def diffIp(actions: ArrayBuffer[FlowAction]): Unit =
        if (!matchObjectsSame(origMatch.getNetworkSrcIP,
                              wcmatch.getNetworkSrcIP) ||
            !matchObjectsSame(origMatch.getNetworkDstIP,
                              wcmatch.getNetworkDstIP) ||
            !matchObjectsSame(origMatch.getNetworkTTL,
                              wcmatch.getNetworkTTL)) {
            actions.append(setKey(
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

    private def diffVlan(actions: ArrayBuffer[FlowAction]): Unit =
        if (!origMatch.getVlanIds.equals(wcmatch.getVlanIds)) {
            val vlansToRemove = origMatch.getVlanIds.diff(wcmatch.getVlanIds)
            val vlansToAdd = wcmatch.getVlanIds.diff(origMatch.getVlanIds)
            log.debug("Vlan tags to pop {}, vlan tags to push {}",
                vlansToRemove, vlansToAdd)

            for (vlan <- vlansToRemove) {
                actions.append(popVLAN())
            }

            var count = vlansToAdd.size
            for (vlan <- vlansToAdd) {
                count -= 1
                val protocol = if (count == 0) Ethernet.VLAN_TAGGED_FRAME
                else Ethernet.PROVIDER_BRIDGING_TAG
                actions.append(FlowActions.pushVLAN(vlan, protocol))
            }
        }

    private def diffIcmp(actions: ArrayBuffer[FlowAction]): Unit = {
        val icmpData = wcmatch.getIcmpData
        if ((icmpData ne null) &&
            !Arrays.equals(icmpData, origMatch.getIcmpData)) {

            val icmpType = wcmatch.getSrcPort
            if (icmpType == ICMP.TYPE_PARAMETER_PROBLEM ||
                    icmpType == ICMP.TYPE_UNREACH ||
                    icmpType == ICMP.TYPE_TIME_EXCEEDED) {

                actions.append(setKey(FlowKeys.icmpError(
                    wcmatch.getSrcPort.byteValue(),
                    wcmatch.getDstPort.byteValue(),
                    wcmatch.getIcmpData
                )))
            }
        }
    }

    private def diffL4(actions: ArrayBuffer[FlowAction]): Unit =
        if (!matchObjectsSame(origMatch.getSrcPort,
            wcmatch.getSrcPort) ||
                !matchObjectsSame(origMatch.getDstPort,
                    wcmatch.getDstPort)) {

            wcmatch.getNetworkProto.byteValue() match {
                case TCP.PROTOCOL_NUMBER =>
                    actions.append(setKey(FlowKeys.tcp(
                        wcmatch.getSrcPort,
                        wcmatch.getDstPort)))
                case UDP.PROTOCOL_NUMBER =>
                    actions.append(setKey(FlowKeys.udp(
                        wcmatch.getSrcPort,
                        wcmatch.getDstPort)))
                case ICMP.PROTOCOL_NUMBER =>
                // this case would only happen if icmp id in ECHO req/reply
                // were translated, which is not the case, so leave alone
            }
        }

    override def toString = s"PacketContext[$cookieStr]"
}
