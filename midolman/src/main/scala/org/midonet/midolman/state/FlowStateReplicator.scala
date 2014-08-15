/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.util.{UUID, List => JList, Set => JSet, Iterator => JIterator,
                  HashSet => JHashSet, ArrayList}

import scala.collection.mutable
import akka.actor.ActorSystem

import com.google.protobuf.MessageLite

import org.midonet.cluster.client.Port
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.{NotYetException, UnderlayResolver}
import org.midonet.midolman.simulation.PortGroup
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.topology.{VirtualTopologyActor => VTA,
                                      VirtualToPhysicalMapper => VTPM}
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest
import org.midonet.odp.{Datapath, FlowMatches, Packet}
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions.setKey
import org.midonet.odp.flows.FlowKeys.tunnel
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets.Ethernet
import org.midonet.rpc.{FlowStateProto => Proto}
import org.midonet.sdn.state.{FlowStateTable, FlowStateTransaction}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.FixedArrayOutputStream
import org.midonet.util.collection.Reducer
import org.midonet.util.functors.Callback0

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

    def conntrackTable: FlowStateTable[ConnTrackKey, ConnTrackValue]
    def natTable: FlowStateTable[NatKey, NatBinding]
    def storage: FlowStateStorage
    def underlay: UnderlayResolver
    def datapath: Datapath
    protected def log: akka.event.LoggingAdapter
    protected def invalidateFlowsFor: (FlowStateKey) => Unit
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

    private val _conntrackAdder = new Reducer[ConnTrackKey, ConnTrackValue, ArrayList[Callback0]] {
        override def apply(callbacks: ArrayList[Callback0], k: ConnTrackKey,
                           v: ConnTrackValue): ArrayList[Callback0] = {
            if (txPeers.size() > 0) {
                log.debug("push conntrack key: {}", k)
                txState.setConntrackKey(connTrackKeyToProto(k))
            }
            log.debug("touch conntrack key: {}", k)
            storage.touchConnTrackKey(k, txIngressPort, txPorts.iterator())

            callbacks.add(new Callback0 {
                override def call(): Unit = conntrackTable.unref(k)
            })
            callbacks
        }
    }

    private val _natAdder = new Reducer[NatKey, NatBinding, ArrayList[Callback0]] {
        override def apply(callbacks: ArrayList[Callback0], k: NatKey,
                           v: NatBinding): ArrayList[Callback0] = {
            if (txPeers.size() > 0) {
                log.debug("push nat key: {}", k)
                txNatEntry.clear()
                txNatEntry.setK(natKeyToProto(k)).setV(natBindingToProto(v))
                txState.addNatEntries(txNatEntry.build())
            }
            log.debug("touch nat key: {}", k)
            storage.touchNatKey(k, v, txIngressPort, txPorts.iterator())

            callbacks.add(new Callback0 {
                override def call(): Unit = natTable.unref(k)
            })
            callbacks
        }
    }

    private def resetCurrentMessage() {
        currentMessage.clear()
        currentMessage.setSender(hostId)
        currentMessage.setEpoch(underlay.host.epoch)
        /* We don't expect ACKs, seq is unused for now */
        currentMessage.setSeq(0x1)
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
                          callbacks: ArrayList[Callback0]): Unit = {
        if (natTx.size() == 0 && conntrackTx.size() == 0)
            return

        resolvePeers(ingressPort, egressPort, egressPortSet, txPeers, txPorts, tags)
        val hasPeers = !txPeers.isEmpty

        if (hasPeers) {
            txState.clear()
            resetCurrentMessage()
        }

        txIngressPort = ingressPort
        conntrackTx.fold(callbacks, _conntrackAdder)
        natTx.fold(callbacks, _natAdder)

        if (hasPeers)
            buildMessage(ingressPort, egressPort, egressPortSet)
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

    private def acceptNewState(msg: Proto.StateMessage) {
        val newStates = msg.getNewStateList.iterator
        while (newStates.hasNext) {
            val state = newStates.next()
            if (state.hasConntrackKey) {
                val k = connTrackKeyFromProto(state.getConntrackKey)
                log.debug("got new conntrack key: {}", k)
                conntrackTable.touch(k, ConnTrackState.RETURN_FLOW)
                invalidateFlowsFor(k)
            }

            val natEntries = state.getNatEntriesList.iterator
            while (natEntries.hasNext) {
                val nat = natEntries.next()
                val k = natKeyFromProto(nat.getK)
                val v = natBindingFromProto(nat.getV)
                log.debug("Got new nat mapping: {} -> {}", k, v)
                natTable.touch(k, v)
                invalidateFlowsFor(k)
            }
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

        log.debug("Got state replication message from: {}", msg.getSender)
        acceptNewState(msg)
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
                               tags: mutable.Set[FlowTag]): Unit = {
        hosts.clear()
        ports.clear()
        collectPeersForPort(ingressPort, hosts, ports, tags)
        if (egressPort != null) {
            ports.add(egressPort)
            collectPeersForPort(egressPort, hosts, ports, tags)
        } else if (egressPortSet != null) {
            collectPeersForPortSet(egressPortSet, hosts, tags)
        }

        log.debug("Resolved peers {}", hosts)
    }
}

class FlowStateReplicator(
        override val conntrackTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
        override val natTable: FlowStateTable[NatKey, NatBinding],
        override val storage: FlowStateStorage,
        override val underlay: UnderlayResolver,
        override val invalidateFlowsFor: (FlowStateKey) => Unit,
        override val datapath: Datapath)(implicit as: ActorSystem)
        extends BaseFlowStateReplicator {

    override val log = akka.event.Logging(as, this.getClass)

    @throws(classOf[NotYetException])
    override def getPort(id: UUID) = VTA.tryAsk[Port](id)

    @throws(classOf[NotYetException])
    override def getPortGroup(id: UUID) = VTA.tryAsk[PortGroup](id)

    @throws(classOf[NotYetException])
    override def getPortSet(id: UUID) = VTPM.tryAsk(PortSetRequest(id))
}
