/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.text.SimpleDateFormat
import java.util.{Date, Set => JSet, UUID}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import akka.actor.ActorSystem

import org.midonet.cache.Cache
import org.midonet.cluster.client.Port
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.ChainPacketContext
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.odp.flows.{FlowActions, FlowKeys, FlowAction}
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.Packet
import org.midonet.packets._
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.flows.FlowTagger.FlowTag
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
                    val connectionCache: Cache,
                    val traceMessageCache: Cache,
                    val traceIndexCache: Cache,
                    val parentCookie: Option[Int],
                    val origMatch: WildcardMatch)
                   (implicit actorSystem: ActorSystem)
         extends ChainPacketContext {
    import PacketContext._

    private val log =
        LoggerFactory.getActorSystemThreadLog(this.getClass)(actorSystem.eventStream)
    // ingressFE is used for connection tracking. conntrack keys use the
    // forward flow's egress device id. For return packets, symmetrically,
    // the ingress device is used to lookup the conntrack key that would have
    // been written by the forward packet. PacketContext needs to now
    // the ingress device to do this lookup in isForwardFlow()
    private var ingressFE: UUID = null

    var portGroups: JSet[UUID] = null
    private var forwardFlow = false
    private var connectionTracked = false
    override def isConnTracked: Boolean = connectionTracked

    private var _inPortId: UUID = null
    override def inPortId: UUID = _inPortId
    def inPortId_=(port: Port) {
        _inPortId = if (port == null) null else port.id
        // ingressFE is set only once, so it always points to the
        // first device that saw this packet, null for generated packets.
        if (port != null && ingressFE == null && !isGenerated)
            ingressFE = port.deviceID

    }

    var lastInvalidation: Long = _

    var idle: Boolean = true
    var runs: Int = 0

    var outPortId: UUID = null
    val wcmatch = new WildcardMatch

    private var traceID: UUID = null
    private var traceStep = 0
    private var isTraced = false

    def inputPort = origMatch.getInputPortUUID
    def inputPort_=(inputPortUUID: UUID): Unit = {
        origMatch.setInputPortUUID(inputPortUUID)
        wcmatch.setInputPortUUID(inputPortUUID)
    }

    // This set stores the callback to call when this flow is removed.
    val flowRemovedCallbacks = mutable.ListBuffer[Callback0]()
    override def addFlowRemovedCallback(cb: Callback0): Unit =
        flowRemovedCallbacks.append(cb)

    def runFlowRemovedCallbacks() = {
        val iterator = flowRemovedCallbacks.iterator
        while (iterator.hasNext) {
            iterator.next().call()
        }
        flowRemovedCallbacks.clear()
    }

    def ethernet = packet.getEthernet

    def isGenerated = cookieOrEgressPort.isRight
    def ingressed = cookieOrEgressPort.isLeft

    def flowCookie = cookieOrEgressPort.left.toOption

    val cookieStr = (if (isGenerated) "[genPkt:" else "[cookie:") +
                 flowCookie.getOrElse(parentCookie.getOrElse("No Cookie")) + "]"

    // This Set stores the tags by which the flow may be indexed.
    // The index can be used to remove flows associated with the given tag.
    val flowTags = mutable.Set[FlowTag]()
    override def addFlowTag(tag: FlowTag): Unit =
        flowTags.add(tag)

    def prepareForSimulation(lastInvalidationSeen: Long) {
        idle = false
        runs += 1
        lastInvalidation = lastInvalidationSeen
        wcmatch.reset(origMatch)
    }

    def postpone() {
        idle = true
        flowRemovedCallbacks.clear()
        flowTags.clear()
    }

    var traceConditions: TraceConditions = null

    def setTraced(flag: Boolean) {
        if (flag && traceMessageCache == null) {
            log.error("Attempting to trace with no message cache")
            return
        }
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
            val value: String = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS "
                                ).format(new Date)) + equipStr + " " + msg
            traceMessageCache.set(key, value)
            traceIndexCache.set(traceID.toString, traceStep.toString)
        }
    }

    override def isForwardFlow: Boolean = {

        // Packets which aren't TCP-or-UDP over IPv4 aren't connection
        // tracked, and always treated as forward flows.
        if (!IPv4.ETHERTYPE.equals(origMatch.getEtherType) ||
            (!TCP.PROTOCOL_NUMBER.equals(origMatch.getNetworkProtocol) &&
             !UDP.PROTOCOL_NUMBER.equals(origMatch.getNetworkProtocol) &&
             !ICMP.PROTOCOL_NUMBER.equals(origMatch.getNetworkProtocol)))
            return true

        // Generated packets have ingressFE == null. We will treat all generated
        // tcp udp packets as return flows TODO(rossella) is that
        // enough to determine that it's a return flow?
        if (isGenerated) {
            return false
        } else if (ingressFE == null) {
            throw new IllegalArgumentException(
                    "isForwardFlow cannot be calculated because the ingress " +
                    "device is not set")
        }

        // Connection tracking:  connectionTracked starts out as false.
        // If isForwardFlow is called, connectionTracked becomes true and
        // a lookup into Cassandra determines which direction this packet
        // is considered to be going.
        if (connectionTracked)
            return forwardFlow

        connectionTracked = true
        val key = connectionKey(origMatch.getNetworkSourceIP,
                                icmpIdOrTransportSrc(origMatch),
                                origMatch.getNetworkDestinationIP,
                                icmpIdOrTransportDst(origMatch),
                                origMatch.getNetworkProtocol.toShort,
                                ingressFE)
        // TODO(jlm): Finish org.midonet.cassandra.AsyncCassandraCache
        //            and use it instead.
        val value = connectionCache.get(key)
        forwardFlow = value != "r"
        log.debug("isForwardFlow conntrack lookup - key:{},value:{}", key, value)
        forwardFlow
    }

    def installConnectionCacheEntry(deviceId: UUID) {
        val key = connectionKey(wcmatch.getNetworkDestinationIP,
                                icmpIdOrTransportDst(wcmatch),
                                wcmatch.getNetworkSourceIP,
                                icmpIdOrTransportSrc(wcmatch),
                                wcmatch.getNetworkProtocol.toShort,
                                deviceId)
        log.debug("Installing conntrack entry: key:{}", key)
        connectionCache.set(key, "r")
    }

    private def icmpIdOrTransportSrc(wm: WildcardMatch): Int = {
        if (ICMP.PROTOCOL_NUMBER.equals(wm.getNetworkProtocol)) {
            val icmpId: java.lang.Short = wm.getIcmpIdentifier
            if (icmpId ne null)
                return icmpId.toInt
        }
        wm.getTransportSource
    }

    private def icmpIdOrTransportDst(wm: WildcardMatch): Int = {
        if (ICMP.PROTOCOL_NUMBER.equals(wm.getNetworkProtocol)) {
            val icmpId: java.lang.Short = wm.getIcmpIdentifier
            if (icmpId ne null)
                return icmpId.toInt
        }
        wm.getTransportDestination
    }

    def actionsFromMatchDiff(): ListBuffer[FlowAction] = {
        val actions = ListBuffer[FlowAction]()
        wcmatch.doNotTrackSeenFields()
        origMatch.doNotTrackSeenFields()
        if (!origMatch.getEthernetSource.equals(wcmatch.getEthernetSource) ||
            !origMatch.getEthernetDestination.equals(wcmatch.getEthernetDestination)) {
            actions.append(setKey(FlowKeys.ethernet(
                wcmatch.getEthernetSource.getAddress,
                wcmatch.getEthernetDestination.getAddress)))
        }
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
        // Vlan tag
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

        // ICMP errors
        if (!matchObjectsSame(origMatch.getIcmpData,
            wcmatch.getIcmpData)) {
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
    def matchObjectsSame(orig: AnyRef, modif: AnyRef) =
        (modif eq null) || orig == modif

    override def toString = s"PacketContext[$cookieStr]"
}

object PacketContext {
    def connectionKey(ip1: IPAddr, port1: Int, ip2: IPAddr, port2: Int,
                      proto: Short, deviceID: UUID): String = {
        new StringBuilder(ip1.toString).append('|').append(port1).append('|')
                .append(ip2.toString).append('|')
                .append(port2).append('|').append(proto).append('|')
                .append(deviceID.toString).toString()
    }
}

