/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.lang.{Long => JLong}
import java.util.{UUID, List => JList, Set => JSet, Iterator => JIterator,
                  HashSet => JHashSet, ArrayList, HashMap => JMap}
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import akka.actor.ActorSystem

import com.google.protobuf.MessageLite

import org.midonet.cluster.client.Port
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.{NotYetException, UnderlayResolver}
import org.midonet.midolman.simulation.PortGroup
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.topology.{VirtualTopologyActor => VTA,
                                      VirtualToPhysicalMapper => VTPM}
import org.midonet.midolman.topology.rcu.{PortSet, Host}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{HostRequest, PortSetRequest}
import org.midonet.odp.{Datapath, FlowMatches, Packet}
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions.setKey
import org.midonet.odp.flows.FlowKeys.tunnel
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets.Ethernet
import org.midonet.rpc.{FlowStateProto => Proto}
import org.midonet.sdn.state.{FlowStateLifecycle, FlowStateTransaction}
import org.midonet.sdn.state.FlowStateTable.Reducer
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.{FlowTag, FlowStateTag}
import org.midonet.util.FixedArrayOutputStream
import org.midonet.util.functors.Callback0

/**
 * Dead simple thread-unsafe class to keep book keeping data for locally owned
 * keys. It tracks ingressPort-key-peer associations.
 */
class StrongRefLibrarian {
    val ingressPortToKeys = new JMap[UUID, JSet[FlowStateTag]]()
    val keyToIngressPort = new JMap[FlowStateTag, UUID]()
    val keyToPeers = new JMap[FlowStateTag, JSet[UUID]]()

    val NO_PEERS = new JHashSet[UUID]()

    def isLocallyOwned(key: FlowStateTag): Boolean = keyToIngressPort.containsKey(key)

    def peersForKey(key: FlowStateTag): JSet[UUID] = keyToPeers.get(key)

    def ingressPortForKey(key: FlowStateTag) = keyToIngressPort.get(key)

    def claimOwnership(ingressPort: UUID, key: FlowStateTag) {
        if (!isLocallyOwned(key)) {
            keyToIngressPort.put(key, ingressPort)
            keyToPeers.put(key, NO_PEERS)
        }
    }

    def addPeersFor(key: FlowStateTag, peers: JSet[UUID]) {
        val current = keyToPeers.get(key)
        val keyIsOwned = current ne null
        if (keyIsOwned) {
            if (current eq NO_PEERS)
                keyToPeers.put(key, peers)
            else
                current.addAll(peers)
        }
    }

    def forgetKey(key: FlowStateTag) {
        if (isLocallyOwned(key)) {
            keyToIngressPort.remove(key)
            keyToPeers.remove(key)
        }
    }
}

/**
 * Dead simple thread-unsafe class to keep book keeping data for remotely owned
 * keys. It tracks ingressPort-key-owner associations.
 *
 * The unref operations are, however, thread-safe, as they simply iterate through
 * the relevant keys to call an unref callback.
 */
class WeakRefLibrarian {
    val ingressPortToKeys = new ConcurrentHashMap[UUID, JSet[FlowStateTag]]()
    val keyToIngressPort = new JMap[FlowStateTag, UUID]()
    val peerToIngressPorts = new ConcurrentHashMap[UUID, JSet[UUID]]()
    val ingressPortToPeer = new JMap[UUID, UUID]()

    def isRemotelyOwned(key: FlowStateTag) = keyToIngressPort.containsKey(key)

    def learnKey(peer: UUID, ingressPort: UUID, key: FlowStateTag) {
        val peerPorts = peerToIngressPorts.get(peer)
        if ((keyToIngressPort.get(key) == ingressPort) &&
            (peerPorts ne null) && peerPorts.contains(ingressPort)) {
            return
        }

        forgetKey(key)

        if (ingressPortToKeys.get(ingressPort) eq null)
            ingressPortToKeys.put(ingressPort, new JHashSet[FlowStateTag]())
        ingressPortToKeys.get(ingressPort).add(key)
        keyToIngressPort.put(key, ingressPort)

        if (peerToIngressPorts.get(peer) eq null)
            peerToIngressPorts.put(peer, new JHashSet[UUID]())
        peerToIngressPorts.get(peer).add(ingressPort)
        ingressPortToPeer.put(ingressPort, peer)
    }

    /* thread-safe read-only */
    def unrefPortKeys(port: UUID, unrefKeyCb: (FlowStateTag) => Unit) {
        val keys = ingressPortToKeys.get(port)
        if (keys == null)
            return

        val it = keys.iterator()
        while (it.hasNext) {
            val k = it.next()
            unrefKeyCb(k)
        }
    }

    /* thread-safe read-only */
    def unrefPeerKeys(peer: UUID, unrefKeyCb: (FlowStateTag) => Unit) {
        if (peerToIngressPorts.containsKey(peer)) {
            val ports = peerToIngressPorts.get(peer).iterator()
            while (ports.hasNext)
                unrefPortKeys(ports.next(), unrefKeyCb)
        }
    }

    def forgetKey(key: FlowStateTag) {
        val port = keyToIngressPort.get(key)
        if (port ne null) {
            keyToIngressPort.remove(key)
            ingressPortToKeys.get(port).remove(key)

            if (ingressPortToKeys.get(port).size() == 0) {
                ingressPortToKeys.remove(port)
                val peer = ingressPortToPeer.remove(port)
                peerToIngressPorts.get(peer).remove(port)
            }
        }
    }
}


/**
 * A class to replicate per-flow connection state between interested hosts.
 *
 * Sample usage:
 *
 * <code>
 * replicator.accumulateNewKeys(natTx, conntrackTx, ingressPort, egressPort, null)
 * replicator.pushState()
 * natTx.commit()
 * conntrackTx.commit()
 *
 * natTable.expireIdleEntries(interval, replicator.natRemover)
 * conntrackTable.expireIdleEntries(interval, replicator.conntrackRemover)
 * replicator.pushState()
 *
 * replicator.accept(packet)
 * </code>
 *
 *          NOTES ON THREAD SAFETY
 *          **********************
 *
 * This class is meant to be associated with a single shard pair (nat, conntrack)
 * of a ShardedFlowStateTable table. For this reason the class happily uses
 * unsynchronized internal state, it's NOT thread-safe.
 *
 * The expected ownership semantics are:
 *
 *   1 This class will push and keep track of state produced by a single
 *     packet processing thread.
 *
 *   2 Because of 1), key expirations are expected to be handled on the same
 *     thread, so that the same replicator will be the one to see the
 *     expirations, making use of the internal state that keeps track of which
 *     hosts received what.
 *
 *   3 Received state will always be processed by the same packet processing
 *     thread, because state packets have all the same flow match. It will be
 *     written to its local shard.
 *
 *   4 Received deletion notifications will also be processed by the same
 *     packet processing thread, because of the flow match. This guarantees
 *     that the deletion operation will not cross over to other shards.
 *
 *   5 Other threads' read operations will spill over to this shard for the
 *     received keys.
 */
abstract class BaseFlowStateReplicator() {
    import FlowStatePackets._

    def conntrackTable: FlowStateLifecycle[ConnTrackKey, ConnTrackValue]
    def natTable: FlowStateLifecycle[NatKey, NatBinding]
    def storage: FlowStateStorage
    def underlay: UnderlayResolver
    def datapath: Datapath
    protected def log: akka.event.LoggingAdapter
    protected def invalidateFlowsFor: (FlowStateTag) => Unit
    protected def getHost(id: UUID): Host
    protected def getPort(id: UUID): Port
    protected def getPortSet(id: UUID): PortSet
    protected def getPortGroup(id: UUID): PortGroup

    /* Used for message building */
    private[this] val txState = Proto.FlowState.newBuilder()
    private[this] val txNatEntry = Proto.NatEntry.newBuilder()
    private[this] val currentMessage = Proto.StateMessage.newBuilder()
    private[this] var txIngressPort: UUID = _
    private[this] val txPeers: JSet[UUID] = new JHashSet[UUID]()
    private[this] val txPorts: JSet[UUID] = new JHashSet[UUID]()

    private[this] val pendingMessages = new ArrayList[(JSet[UUID], MessageLite)]()
    private[this] val hostId = uuidToProto(underlay.host.id)

    /* Used for packet building
     * FIXME(guillermo) - use MTU
     */
    private[this] val buffer = new Array[Byte](1500 - OVERHEAD)
    private[this] val stream = new FixedArrayOutputStream(buffer)
    private[this] val packet = {
        val udpShell = makeUdpShell(buffer)
        new Packet(udpShell, FlowMatches.fromEthernetPacket(udpShell))
    }

    /* Book-keeping data structures
     *
     * ownedKeys: manages keys owned by this host. Needs not be thread-safe,
     *            no methods that may be called by foreign threads call into it
     * borrowedKeys: manages keys owned by other hosts. Needs not be thread-safe
     *               except for iterating over the keys of a peer or port.
     * epochGraveyard: tracks peers' epochs that have been invalidated
     */
    private[this] val ownedKeys = new StrongRefLibrarian()
    private[this] val borrowedKeys = new WeakRefLibrarian()
    private[this] val epochGraveyard = new ConcurrentHashMap[UUID, JLong]()


    private def isZombie(peer: UUID, epoch: Long): Boolean = {
        val deadEpoch = epochGraveyard.get(peer)
        if (deadEpoch eq null)
            false
        else
            deadEpoch >= epoch

    }

    private val _conntrackAdder = new Reducer[ConnTrackKey, ConnTrackValue, StrongRefLibrarian] {
        override def apply(refs: StrongRefLibrarian, k: ConnTrackKey, v: ConnTrackValue) = {
            val isNewKey = conntrackTable.get(k) eq null
            if (refs.isLocallyOwned(k) || isNewKey) {
                log.debug("push-add conntrack key: {}", k)
                if (txPeers.size() > 0)
                    txState.setConntrackKey(connTrackKeyToProto(k))
                refs.claimOwnership(txIngressPort, k)
                refs.addPeersFor(k, txPeers)
            }
            if (isNewKey)
                storage.touchConnTrackKey(k, txIngressPort, txPorts.iterator())
            refs
        }
    }

    private val _natAdder = new Reducer[NatKey, NatBinding, StrongRefLibrarian] {
        override def apply(refs: StrongRefLibrarian, k: NatKey, v: NatBinding) = {
            val isNewKey = natTable.get(k) eq null
            if (refs.isLocallyOwned(k) || isNewKey) {
                log.debug("push-add nat key: {}", k)
                if (txPeers.size() > 0) {
                    txNatEntry.clear()
                    txNatEntry.setK(natKeyToProto(k)).setV(natBindingToProto(v))
                    txState.addNatEntries(txNatEntry.build())
                }
                refs.claimOwnership(txIngressPort, k)
                refs.addPeersFor(k, txPeers)
            }
            if (isNewKey)
                storage.touchNatKey(k, v, txIngressPort, txPorts.iterator())
            refs
        }
    }

    private val _conntrackCallbacks = new Reducer[ConnTrackKey, ConnTrackValue, ArrayList[Callback0]] {
        override def apply(callbacks: ArrayList[Callback0],
                           key: ConnTrackKey,
                           value: ConnTrackValue): ArrayList[Callback0] = {
            callbacks.add(new Callback0 {
                override def call(): Unit = conntrackTable.unref(key)
            })
            callbacks
        }
    }

    private val _natCallbacks = new Reducer[NatKey, NatBinding, ArrayList[Callback0]] {
        override def apply(callbacks: ArrayList[Callback0],
                           key: NatKey,
                           value: NatBinding): ArrayList[Callback0] = {
            callbacks.add(new Callback0 {
                override def call(): Unit = natTable.unref(key)
            })
            callbacks
        }
    }

    private def isKeyStillLocallyOwned(key: FlowStateTag): Boolean = {
        val portId = ownedKeys.ingressPortForKey(key)
        if (portId eq null)
            false
        else
            getPort(portId).hostID == underlay.host.id
    }

    /**
     * Reducer instance meant to be passed to a FlowStateLifecycle
     * instance as it expires idle entries from the table. This reducer will
     * make the replicator prepare messages to push deletion requests for the
     * deleted keys. Once the reducer has been used, you will want to invoke
     * pushState() to have the replicator send out the messages it prepared.
     *
     * EXPECTED CALLING THREADS: only the packet processing thread that owns
     * this replicator.
     */
    val conntrackRemover = new Reducer[ConnTrackKey, ConnTrackValue, BaseFlowStateReplicator] {
        override def apply(s: BaseFlowStateReplicator, k: ConnTrackKey, v: ConnTrackValue) = {
            if (isKeyStillLocallyOwned(k)) {
                val peers = ownedKeys.peersForKey(k)
                if ((peers ne null) && !peers.isEmpty) {
                    resetCurrentMessage()
                    log.debug("push-delete for key: {}", k)
                    currentMessage.addDeleteConntrackKeys(connTrackKeyToProto(k))
                    pendingMessages.add((peers, currentMessage.build()))
                }
            } else if (borrowedKeys.isRemotelyOwned(k)) {
                borrowedKeys.forgetKey(k)
            }
            ownedKeys.forgetKey(k)
            s
        }
    }

    /** See conntrackRemover above */
    val natRemover = new Reducer[NatKey, NatBinding, BaseFlowStateReplicator] {
        override def apply(s: BaseFlowStateReplicator, k: NatKey, v: NatBinding) = {
            if (isKeyStillLocallyOwned(k)) {
                val peers = ownedKeys.peersForKey(k)
                if ((peers ne null) && !peers.isEmpty) {
                    resetCurrentMessage()
                    log.debug("push-delete for key: {}", k)
                    currentMessage.addDeleteNatKeys(natKeyToProto(k))
                    pendingMessages.add((peers, currentMessage.build()))
                }
            } else if (borrowedKeys.isRemotelyOwned(k)) {
                borrowedKeys.forgetKey(k)
            }
            ownedKeys.forgetKey(k)
            s
        }
    }

    private def resetCurrentMessage() {
        currentMessage.clear()
        currentMessage.setSender(hostId)
        currentMessage.setEpoch(underlay.host.epoch)
        /* We don't expect ACKs, seq is unused for now */
        currentMessage.setSeq(0x1)
    }

    private val weakKeyUnrefCb: (FlowStateTag) => Unit = {
        case k: NatKey =>
            natTable.setRefCount(k, 0)
        case k: ConnTrackKey =>
            conntrackTable.setRefCount(k, 0)
    }

    def importFromStorage(batch: FlowStateBatch) {
        importConnTrack(batch.strongConnTrack.iterator(), ConnTrackState.FORWARD_FLOW)
        importConnTrack(batch.weakConnTrack.iterator(), ConnTrackState.RETURN_FLOW)
        importNat(batch.strongNat.entrySet().iterator())
        importNat(batch.weakNat.entrySet().iterator())
    }

    private def importConnTrack(keys: JIterator[ConnTrackKey], v: ConnTrackState.ConnTrackValue) {
        while (keys.hasNext) {
            val k = keys.next()
            conntrackTable.putAndRef(k, v)
            conntrackTable.unref(k)
        }
    }

    private def importNat(entries: JIterator[java.util.Map.Entry[NatKey, NatBinding]]) {
        while (entries.hasNext) {
            val e = entries.next()
            natTable.putAndRef(e.getKey, e.getValue)
            natTable.unref(e.getKey)
        }
    }

    /**
     * Ask this replicator to unref the keys owned by a given peer. This method
     * is thread-safe.
     *
     * EXPECTED CALLING THREADS: any.
     */
    def forgetPeer(peer: Host) {
        /* We must put the dead peer's epoch in the graveyard before unrefing its
         * keys. This guarantees that a message sent by the dead peer before its
         * demise will either arrive to 'borrowedKeys' before this cleanup or
         * find the dead epoch in the graveyard when accept() processes it.
         *
         * FIXME(guillermo) the same race is possible in the port migration case
         * but we can't close the gap without adding version numbers to port
         * objects in the cluster.
         */
        epochGraveyard.put(peer.id, peer.epoch)
        borrowedKeys.unrefPeerKeys(peer.id, weakKeyUnrefCb)
    }

    /**
     * Ask this replicator to unref the remotely owned keys associated with an
     * ingress port. This method is thread-safe.
     *
     * EXPECTED CALLING THREADS: any.
     */
    def portChanged(oldPort: Port, newPort: Port) {
        if (oldPort.hostID != newPort.hostID) {
            if (oldPort.hostID != underlay.host.id)
                borrowedKeys.unrefPortKeys(oldPort.id, weakKeyUnrefCb)
            /* If the port belonged to this host, do nothing. We will
               remove the port's keys from the StrongRefLibrarian lazily when
               they idle out. */
        }
    }

    /**
     * Given the FlowStateTransaction instances resulting from the processing
     * of a flow, this method will prepare messages to push the state accumulated
     * in those transactions to the relevant hosts. It is assumed that the
     * transaction is going to be committed unless this method returns a NotYet.
     *
     * EXPECTED CALLING THREADS: only the packet processing thread that owns
     * this replicator.
     *
     * @param natTx The nat table transaction to collect new keys from.
     * @param conntrackTx The conntrack table transaction to collect new keys
     *                    from.
     * @param ingressPort Ingress port id for the packet that originated the new
     *                    keys.
     * @param egressPort The egress port id, or null if the packet egressed a
     *                   port set.
     * @param egressPortSet The egress port set id, or null if the packet
     *                      egressed an unicast port.
     * @param tags A mutable set to collect tags that will invalidate the
     *             soon-to-be-installed flow. The caller is responsible
     *             for tagging the flow.
     * @param callbacks A mutable list of callbacks that will be called when the
     *                  current flow is deleted.
     *
     * @throws NotYetException This agent is missing pieces of topology in its
     *                         local cache that would be necessary in order to
     *                         calculate the peers that should receive this keys.
     */
    @throws(classOf[NotYetException])
    def accumulateNewKeys(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                          natTx: FlowStateTransaction[NatKey, NatBinding],
                          ingressPort: UUID, egressPort: UUID,
                          egressPortSet: UUID, tags: mutable.Set[FlowTag],
                          callbacks: ArrayList[Callback0]) {
        if (natTx.size() == 0 && conntrackTx.size() == 0)
            return

        resolvePeers(ingressPort, egressPort, egressPortSet, txPeers, txPorts, tags)
        val hasPeers = !txPeers.isEmpty

        if (hasPeers) {
            txState.clear()
            resetCurrentMessage()
            txIngressPort = ingressPort
        }

        handleConntrack(conntrackTx)
        handleNat(natTx)

        if (hasPeers)
            buildMessage(ingressPort, egressPort, egressPortSet)

        conntrackTx.fold(callbacks, _conntrackCallbacks)
        conntrackTx.foldOverRefs(callbacks, _conntrackCallbacks)
        natTx.fold(callbacks, _natCallbacks)
        natTx.foldOverRefs(callbacks, _natCallbacks)
    }

    private def handleConntrack(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue]): Unit =
        if (conntrackTx.size() > 0) {
            conntrackTx.fold(ownedKeys, _conntrackAdder)
            conntrackTx.foldOverRefs(ownedKeys, _conntrackAdder)
         }

    private def handleNat(natTx: FlowStateTransaction[NatKey, NatBinding]): Unit =
        if (natTx.size() > 0) {
            natTx.fold(ownedKeys, _natAdder)
            natTx.foldOverRefs(ownedKeys, _natAdder)
        }

    def buildMessage(ingressPort: UUID, egressPort: UUID, egressPortSet: UUID): Unit =
        if (txState.hasConntrackKey || txState.getNatEntriesCount > 0) {
            txState.setIngressPort(uuidToProto(ingressPort))
            if (egressPort != null)
                txState.setEgressPort(uuidToProto(egressPort))
            else if (egressPortSet != null)
                txState.setEgressPortSet(uuidToProto(egressPortSet))

            currentMessage.addNewState(txState.build())
            pendingMessages.add((txPeers, currentMessage.build()))
        }

    private def hostsToActions(hosts: JSet[UUID]): JList[FlowAction] = {
        val actions = new ArrayList[FlowAction]()
        var i = 0
        val hostsIt = hosts.iterator
        while (hostsIt.hasNext) {
            underlay.peerTunnelInfo(hostsIt.next()) match {
                case Some(route) =>
                    actions.add(setKey(tunnel(TUNNEL_KEY, route.srcIp, route.dstIp)))
                    actions.add(route.output)
                case None =>
            }
            i += 1
        }
        actions
    }

    /**
     * Pushes all of the messages that were previously prepared by natRemover,
     * conntrackRemover and accumulateNewKeys() to their destinations, using the
     * given datapath connection.
     *
     * Packets will be tunneled to their destinations using the usual TunnelZone
     * information and with tunnel key FlowStatePackets.TUNNEL_KEY
     *
     * EXPECTED CALLING THREADS: only the packet processing thread that owns
     * this replicator.
     */
    def pushState(dp: OvsDatapathConnection) {
        var i = pendingMessages.size() - 1
        while (i >= 0) {
            val (hosts, message) = pendingMessages.remove(i)
            if (message.getSerializedSize <= buffer.length) {
                stream.reset()
                message.writeDelimitedTo(stream)
                val actions = hostsToActions(hosts)
                if (!actions.isEmpty)
                    dp.packetsExecute(datapath, packet, actions)
            } else {
                // TODO(guillermo) partition messages
                log.warning("Skipping state message, too large: {}", message)
            }
            i -= 1
        }

        storage.submit()
    }

    private def acceptNewState(msg: Proto.StateMessage, fromThePast: Boolean) {
        val newStates = msg.getNewStateList.iterator
        val sender: UUID = msg.getSender
        while (newStates.hasNext) {
            val state = newStates.next()
            if (state.hasConntrackKey) {
                val k = connTrackKeyFromProto(state.getConntrackKey)
                log.debug("got new conntrack key: {}", k)

                if (conntrackTable.get(k) eq null) {
                    conntrackTable.putAndRef(k, ConnTrackState.RETURN_FLOW)
                }

                if (fromThePast) {
                    conntrackTable.setRefCount(k, 0)
                } else {
                    conntrackTable.setRefCount(k, 1)
                    borrowedKeys.learnKey(sender, state.getIngressPort, k)
                }
                invalidateFlowsFor(k)
            }

            val natEntries = state.getNatEntriesList.iterator
            while (natEntries.hasNext) {
                val nat = natEntries.next()
                val k = natKeyFromProto(nat.getK)
                val v = natBindingFromProto(nat.getV)
                log.debug("Got new nat mapping: {} -> {}", k, v)
                if (natTable.get(k) eq null) {
                    natTable.putAndRef(k, v)
                }

                if (fromThePast) {
                    natTable.setRefCount(k, 0)
                } else {
                    natTable.setRefCount(k, 1)
                    borrowedKeys.learnKey(sender, state.getIngressPort, k)
                }
                invalidateFlowsFor(k)
            }
        }
    }

    private def acceptConntrackRemovals(msg: Proto.StateMessage) {
        val delConntrack = msg.getDeleteConntrackKeysList.iterator
        while (delConntrack.hasNext) {
            val key = connTrackKeyFromProto(delConntrack.next())
            log.debug("Got deletion for conntrack key: {}", key)
            conntrackTable.remove(key)
            invalidateFlowsFor(key)
            borrowedKeys.forgetKey(key)
        }
    }

    private def acceptNatRemovals(msg: Proto.StateMessage) {
        val delNat = msg.getDeleteNatKeysList.iterator
        while (delNat.hasNext) {
            val key = natKeyFromProto(delNat.next())
            log.debug("Got deletion for nat key: {}", key)
            natTable.remove(key)
            invalidateFlowsFor(key)
            borrowedKeys.forgetKey(key)
        }
    }

    /**
     * Processes connection state information contained in a packet that was
     * received through a tunnel using tunnel key FlowStatePackets.TUNNEL_KEY
     *
     * Before parsing the packet, this method will check that the ethernet
     * addresses, ip addresses and udp ports match those defined in
     * FlowStatePackets.
     *
     * EXPECTED CALLING THREADS: only the packet processing thread that owns
     * this replicator.
     */
    @throws(classOf[NotYetException])
    def accept(p: Ethernet) {
        val msg = parseDatagram(p)
        if (msg == null) {
            log.info("Ignoring unexpected packet: {}", p)
            return
        }

        val peer: UUID = msg.getSender
        log.debug("Got state replication message from: {}", peer)

        val fromThePast = (msg.getEpoch < getHost(peer).epoch) || isZombie(peer, msg.getEpoch)
        if (fromThePast)
            log.info("Got a flow state message from a host that has since rebooted")

        acceptNewState(msg, fromThePast)
        acceptConntrackRemovals(msg)
        acceptNatRemovals(msg)
    }

    @throws(classOf[NotYetException])
    private def collectPeersForPort(portId: UUID, hosts: JSet[UUID],
                                    ports: JSet[UUID], tags: mutable.Set[FlowTag]) {
        def addPeerFor(port: Port) {
            if ((port.hostID ne null) && (port.hostID != underlay.host.id))
                hosts.add(port.hostID)
            tags.add(port.deviceTag)
        }

        val port = getPort(portId)
        addPeerFor(port)
        tags.add(port.deviceTag)

        val groupIds = port.portGroups.iterator
        while (groupIds.hasNext) {
            val group = getPortGroup(groupIds.next())
            tags.add(group.deviceTag)
            if (group.stateful) {
                val members = group.members.iterator
                while (members.hasNext) {
                    val id = members.next()
                    if (id != portId) {
                        ports.add(id)
                        addPeerFor(getPort(id))
                    }
                }
            }
        }
    }

    @throws(classOf[NotYetException])
    private def collectPeersForPortSet(psetId: UUID, hosts: JSet[UUID],
                                       tags: mutable.Set[FlowTag]) {
        /* FIXME(guillermo) - this is not checking port groups, but it should
         * be enough because the port set contains all ports in the egress
         * device */
        val portSet = getPortSet(psetId)
        portSet.hosts foreach hosts.add
        tags.add(FlowTagger.tagForBroadcast(psetId, psetId))
    }

    @throws(classOf[NotYetException])
    protected def resolvePeers(ingressPort: UUID,
                               egressPort: UUID,
                               egressPortSet: UUID,
                               hosts: JSet[UUID],
                               ports: JSet[UUID],
                               tags: mutable.Set[FlowTag]) {
        hosts.clear()
        ports.clear()
        collectPeersForPort(ingressPort, hosts, ports, tags)
        if (egressPort != null) {
            ports.add(egressPort)
            collectPeersForPort(egressPort, hosts, ports, tags)
        } else if (egressPortSet != null) {
            collectPeersForPortSet(egressPortSet, hosts, tags)
        }
    }
}

class FlowStateReplicator(
        override val conntrackTable: FlowStateLifecycle[ConnTrackKey, ConnTrackValue],
        override val natTable: FlowStateLifecycle[NatKey, NatBinding],
        override val storage: FlowStateStorage,
        override val underlay: UnderlayResolver,
        override val invalidateFlowsFor: (FlowStateTag) => Unit,
        override val datapath: Datapath)(implicit as: ActorSystem)
        extends BaseFlowStateReplicator {

    override val log = akka.event.Logging(as, this.getClass)

    @throws(classOf[NotYetException])
    override def getHost(id: UUID) = VTPM.tryAsk(HostRequest(id, false))

    @throws(classOf[NotYetException])
    override def getPort(id: UUID) = VTA.tryAsk[Port](id)

    @throws(classOf[NotYetException])
    override def getPortGroup(id: UUID) = VTA.tryAsk[PortGroup](id)

    @throws(classOf[NotYetException])
    override def getPortSet(id: UUID) = VTPM.tryAsk(PortSetRequest(id))
}
