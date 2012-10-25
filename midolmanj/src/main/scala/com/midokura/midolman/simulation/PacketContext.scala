// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

// Read-only view.  Note this is distinct from immutable.Set in that it
// might be changed by another (mutable) view.
import collection.{Set => ROSet, mutable}

import java.util.{Set => JSet, UUID}

import com.midokura.cache.Cache
import com.midokura.midolman.rules.ChainPacketContext
import com.midokura.midolman.util.Net
import com.midokura.packets.{Ethernet, IPv4, TCP, UDP}
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.util.functors.Callback0


/**
 * The PacketContext is a serialization token for the devices during the
 * simulation of a packet's traversal of the virtual topology.
 * A device may not modify the PacketContext after the Future[Action]
 * returned by the process method completes.
 *
 * More specifically:  Ownership of a PacketContext passes to the forwarding
 * element with the call to ForwardingElement::process().  It passes back
 * to the Coordinator when the Future returned by process() completes.
 * Coordinators and ForwardingElements are to read from and write to a
 * PacketContext only during the period in which they own it.
 *
 * TODO: This convention could be made explicit by having the contents
 * of the returned Future contain the PacketContext which the Coordinator
 * was to use and pass on to the next forwarding element, instead of being
 * a singleton-per-simulation token.  Investigate whether that'd be better.
 */
/* TODO(Diyari release): Move inPortID & outPortID out of PacketContext. */
class PacketContext(val flowCookie: Object, val frame: Ethernet,
                    val expiry: Long, val connectionCache: Cache)
         extends ChainPacketContext {
    import PacketContext._
    // PacketContext starts unfrozen, in which mode it can have callbacks
    // and tags added.  Freezing it switches it from write-only to
    // read-only.
    private var frozen = false
    private var wcmatch: WildcardMatch = null
    private var ingressFE: UUID = null
    private var portGroups: JSet[UUID] = null
    private var connectionTracked = false
    private var forwardFlow = false
    private var inPortID: UUID = null
    private var outPortID: UUID = null

    def isFrozen() = frozen

    def getFrame(): Ethernet = frame

    def getExpiry(): Long = expiry

    def setMatch(m: WildcardMatch): PacketContext = {
        wcmatch = m
        this
    }

    def getMatch(): WildcardMatch = wcmatch

    def setIngressFE(fe: UUID): PacketContext = {
        ingressFE = fe
        this
    }

    def setPortGroups(groups: JSet[UUID]): PacketContext = {
        portGroups = groups
        this
    }

    def setInputPort(id: UUID): PacketContext = {
        inPortID = id
        this
    }

    def setOutputPort(id: UUID): PacketContext = {
        outPortID = id
        this
    }

    // This set stores the callback to call when this flow is removed.
    private val flowRemovedCallbacks = mutable.Set[Callback0]()
    def addFlowRemovedCallback(cb: Callback0): Unit = this.synchronized {
        if (frozen)
            throw new IllegalArgumentException(
                            "Adding callback to frozen PacketContext")
        else
            flowRemovedCallbacks.add(cb)
    }
    def getFlowRemovedCallbacks: ROSet[Callback0] = {
        if (!frozen)
            throw new IllegalArgumentException(
                    "Reading callbacks from unfrozen PacketContext")

        flowRemovedCallbacks
    }

    // This Set stores the tags by which the flow may be indexed.
    // The index can be used to remove flows associated with the given tag.
    private val flowTags = mutable.Set[Any]()
    override def addFlowTag(tag: Any): Unit = this.synchronized {
        if (frozen)
            throw new IllegalArgumentException(
                            "Adding tag to frozen PacketContext")
        else
            flowTags.add(tag)
    }
    def getFlowTags: ROSet[Any] = {
        if (!frozen)
            throw new IllegalArgumentException(
                    "Reading tags from unfrozen PacketContext")

        flowTags
    }

    def freeze(): Unit = this.synchronized {
        frozen = true
    }

    def forwardAndReturnAreSymmetric: Boolean = {
        ! (wcmatch.getDataLayerType == IPv4.ETHERTYPE &&
            wcmatch.getNetworkProtocol == TCP.PROTOCOL_NUMBER)
    }

    /* Packet context methods used by Chains. */
    override def getInPortId: UUID = inPortID
    override def getOutPortId: UUID = outPortID
    override def getPortGroups: JSet[UUID] = portGroups
    override def addTraversedElementID(id: UUID) { /* XXX */ }
    override def getFlowCookie: Object = flowCookie
    override def isConnTracked: Boolean = connectionTracked

    override def isForwardFlow: Boolean = {
        // Connection tracking:  connectionTracked starts out as false.
        // If isForwardFlow is called, connectionTracked becomes true and
        // a lookup into Cassandra determines which direction this packet
        // is considered to be going.

        if (connectionTracked)
            return forwardFlow

        // Packets which aren't TCP-or-UDP over IPv4 aren't connection
        // tracked, and always treated as forward flows.
        if (wcmatch.getDataLayerType() != IPv4.ETHERTYPE ||
                (wcmatch.getNetworkProtocol() != TCP.PROTOCOL_NUMBER &&
                 wcmatch.getNetworkProtocol() != UDP.PROTOCOL_NUMBER))
            return true

        connectionTracked = true
        val key = connectionKey(wcmatch.getNetworkSource(),
                                wcmatch.getTransportSource(),
                                wcmatch.getNetworkDestination(),
                                wcmatch.getTransportDestination(),
                                wcmatch.getNetworkProtocol(), ingressFE)
        val value = connectionCache.get(key)
        forwardFlow = (value != "r")
        return forwardFlow
    }
}

object PacketContext {
    def connectionKey(ip1: Int, port1: Short, ip2: Int, port2: Short,
                      proto: Short, deviceID: UUID): String = {
        new StringBuilder(Net.convertIntAddressToString(ip1))
                .append('|').append(port1).append('|')
                .append(Net.convertIntAddressToString(ip2)).append('|')
                .append(port2).append('|').append(proto).append('|')
                .append(deviceID.toString()).toString()
    }
}

