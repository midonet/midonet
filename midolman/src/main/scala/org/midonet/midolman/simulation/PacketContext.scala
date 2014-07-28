/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.text.SimpleDateFormat
import java.util.{Arrays, ArrayList, Date, Set => JSet, UUID}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.actor.ActorSystem

import org.midonet.cluster.client.Port
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.state.FlowStatePackets
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.odp.flows.{FlowActions, FlowKeys, FlowAction}
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.Packet
import org.midonet.packets._
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.flows.FlowTagger.{FlowStateTag, FlowTag}
import org.midonet.util.functors.Callback0

/**
 * The PacketContext represents the simulation of a packet traversing the
 * virtual topology. Since a simulation runs-to-completion, always in the
 * context of the same thread, the PacketContext can be safely mutated and
 * used to pass state between different simulation stages, or between virtual
 * devices.
 */
/* TODO(Diyari release): Move inPortID & outPortID out of PacketContext. */
class PacketContext(val cookieOrEgressPort: Either[Int, UUID],
                    val packet: Packet,
                    val expiry: Long,
                    val parentCookie: Option[Int],
                    val origMatch: WildcardMatch)
                   (implicit actorSystem: ActorSystem) {
    private val log =
        LoggerFactory.getActorSystemThreadLog(this.getClass)(actorSystem.eventStream)

    val state = new StateContext(this, log)
    var portGroups: JSet[UUID] = null

    private var _inPortId: UUID = null
    def inPortId: UUID = _inPortId
    def inPortId_=(port: Port): Unit =
        _inPortId = port.id

    var lastInvalidation: Long = _

    var idle: Boolean = true
    var runs: Int = 0

    var outPortId: UUID = null
    var toPortSet: Boolean = false

    val wcmatch = origMatch.clone()

    private var traceID: UUID = null
    private var traceStep = 0
    private var isTraced = false

    def inputPort = origMatch.getInputPortUUID
    def inputPort_=(inputPortUUID: UUID): Unit = {
        if (inputPortUUID ne null) {
            origMatch.setInputPortUUID(inputPortUUID)
            wcmatch.setInputPortUUID(inputPortUUID)
        }
    }

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
    def isStateMessage = origMatch.getTunnelID == FlowStatePackets.TUNNEL_KEY

    def flowCookie = cookieOrEgressPort.left.toOption

    val cookieStr = (if (isGenerated) "[genPkt:" else "[cookie:") +
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
        if (!origMatch.getEthernetSource.equals(wcmatch.getEthernetSource) ||
            !origMatch.getEthernetDestination.equals(wcmatch.getEthernetDestination)) {
            actions.append(setKey(FlowKeys.ethernet(
                wcmatch.getEthernetSource.getAddress,
                wcmatch.getEthernetDestination.getAddress)))
        }

    private def diffIp(actions: ArrayBuffer[FlowAction]): Unit =
        if (!matchObjectsSame(origMatch.getNetworkSourceIP,
                              wcmatch.getNetworkSourceIP) ||
            !matchObjectsSame(origMatch.getNetworkDestinationIP,
                              wcmatch.getNetworkDestinationIP) ||
            !matchObjectsSame(origMatch.getNetworkTTL,
                              wcmatch.getNetworkTTL)) {
            actions.append(setKey(
                wcmatch.getNetworkSourceIP match {
                    case srcIP: IPv4Addr =>
                        FlowKeys.ipv4(srcIP,
                            wcmatch.getNetworkDestinationIP.asInstanceOf[IPv4Addr],
                            wcmatch.getNetworkProtocol,
                            wcmatch.getNetworkTOS,
                            wcmatch.getNetworkTTL,
                            wcmatch.getIpFragmentType)
                    case srcIP: IPv6Addr =>
                        FlowKeys.ipv6(srcIP,
                            wcmatch.getNetworkDestinationIP.asInstanceOf[IPv6Addr],
                            wcmatch.getNetworkProtocol,
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

            val icmpType = wcmatch.getTransportSource
            if (icmpType == ICMP.TYPE_PARAMETER_PROBLEM ||
                    icmpType == ICMP.TYPE_UNREACH ||
                    icmpType == ICMP.TYPE_TIME_EXCEEDED) {

                actions.append(setKey(FlowKeys.icmpError(
                    wcmatch.getTransportSource.byteValue(),
                    wcmatch.getTransportDestination.byteValue(),
                    wcmatch.getIcmpData
                )))
            }
        }
    }

    private def diffL4(actions: ArrayBuffer[FlowAction]): Unit =
        if (!matchObjectsSame(origMatch.getTransportSource,
            wcmatch.getTransportSource) ||
                !matchObjectsSame(origMatch.getTransportDestination,
                    wcmatch.getTransportDestination)) {

            wcmatch.getNetworkProtocol.byteValue() match {
                case TCP.PROTOCOL_NUMBER =>
                    actions.append(setKey(FlowKeys.tcp(
                        wcmatch.getTransportSource,
                        wcmatch.getTransportDestination)))
                case UDP.PROTOCOL_NUMBER =>
                    actions.append(setKey(FlowKeys.udp(
                        wcmatch.getTransportSource,
                        wcmatch.getTransportDestination)))
                case ICMP.PROTOCOL_NUMBER =>
                // this case would only happen if icmp id in ECHO req/reply
                // were translated, which is not the case, so leave alone
            }
        }

    override def toString = s"PacketContext[$cookieStr]"
}
