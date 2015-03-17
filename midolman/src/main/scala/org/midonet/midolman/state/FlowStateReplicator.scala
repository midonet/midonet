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

package org.midonet.midolman.state

import java.util.{ArrayList, HashSet => JHashSet, Iterator => JIterator, List => JList, Set => JSet, UUID}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.midolman.flows.FlowInvalidation
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.simulation.{PacketContext, PortGroup}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.devices.Port
import org.midonet.midolman.topology.{VirtualTopologyActor => VTA}
import org.midonet.midolman.{NotYetException, UnderlayResolver}
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions.setKey
import org.midonet.odp.flows.FlowKeys.tunnel
import org.midonet.packets.Ethernet
import org.midonet.rpc.{FlowStateProto => Proto}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.state.FlowStateTable
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
abstract class BaseFlowStateReplicator(conntrackTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
                                       natTable: FlowStateTable[NatKey, NatBinding],
                                       storage: FlowStateStorage,
                                       underlay: UnderlayResolver,
                                       flowInvalidation: FlowInvalidation,
                                       tos: Byte) {
    import FlowStatePackets._

    protected def log: Logger
    protected def getPort(id: UUID): Port
    protected def getPortGroup(id: UUID): PortGroup

    /* Used for message building */
    private[this] val txState = Proto.FlowState.newBuilder()
    private[this] val txNatEntry = Proto.NatEntry.newBuilder()
    private[this] val currentMessage = Proto.StateMessage.newBuilder()
    private[this] var txIngressPort: UUID = _
    private[this] val txPeers: JSet[UUID] = new JHashSet[UUID]()
    private[this] val txPorts: JSet[UUID] = new JHashSet[UUID]()

    private[this] val hostId = uuidToProto(underlay.host.id)

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
        currentMessage.setEpoch(0L /* the epoch is not used*/)

        /* We don't expect ACKs, seq is unused for now */
        currentMessage.setSeq(0x1)
    }

    def importFromStorage(batch: FlowStateBatch) {
        importConnTrack(batch.strongConnTrack.iterator(), ConnTrackState.RETURN_FLOW)
        importConnTrack(batch.weakConnTrack.iterator(), ConnTrackState.RETURN_FLOW)
        importNat(batch.strongNat.entrySet().iterator())
        importNat(batch.weakNat.entrySet().iterator())
    }

    private def importConnTrack(keys: JIterator[ConnTrackKey], v: ConnTrackState.ConnTrackValue) {
        while (keys.hasNext) {
            val k = keys.next()
            log.debug("importing state key from storage: {}", k)
            conntrackTable.putAndRef(k, v)
            conntrackTable.unref(k)
        }
    }

    private def importNat(entries: JIterator[java.util.Map.Entry[NatKey, NatBinding]]) {
        while (entries.hasNext) {
            val e = entries.next()
            log.debug("importing state key from storage: {}", e.getKey)
            natTable.putAndRef(e.getKey, e.getValue)
            natTable.unref(e.getKey)
        }
    }

    /**
     * Given the FlowStateTransaction instances resulting from the processing
     * of a flow, this method will prepare messages to push the state accumulated
     * in those transactions to the relevant hosts. It is assumed that the
     * transaction is going to be committed unless this method throws a NotYetException.
     *
     * EXPECTED CALLING THREADS: only the packet processing thread that owns
     * this replicator.
     */
    @throws(classOf[NotYetException])
    def accumulateNewKeys(context: PacketContext): Unit = {
        val ingressPort = context.inputPort
        val egressPorts = context.outPorts
        resolvePeers(ingressPort, egressPorts, txPeers, txPorts, context.flowTags)
        txIngressPort = ingressPort
        val callbacks = context.flowRemovedCallbacks
        context.conntrackTx.fold(callbacks, _conntrackAdder)
        context.natTx.fold(callbacks, _natAdder)
        buildMessage(context, ingressPort)
    }

    def buildMessage(context: PacketContext, ingressPort: UUID): Unit =
        if (!txPeers.isEmpty) {
            resetCurrentMessage()
            txState.setIngressPort(uuidToProto(ingressPort))
            currentMessage.addNewState(txState.build())
            context.stateMessage = currentMessage.build()
            hostsToActions(txPeers, context.stateActions)
            txState.clear()
        }

    private def hostsToActions(hosts: JSet[UUID],
                               actions: ArrayList[FlowAction]): Unit = {
        val hostsIt = hosts.iterator
        while (hostsIt.hasNext) {
            underlay.peerTunnelInfo(hostsIt.next()) match {
                case Some(route) =>
                    val key = setKey(tunnel(TUNNEL_KEY, route.srcIp, route.dstIp, tos))
                    actions.add(key)
                    actions.add(route.output)
                case None =>
            }
        }
    }

    def touchState(): Unit =
        storage.submit()

    private def acceptNewState(msg: Proto.StateMessage) {
        val newStates = msg.getNewStateList.iterator
        while (newStates.hasNext) {
            val state = newStates.next()
            if (state.hasConntrackKey) {
                val k = connTrackKeyFromProto(state.getConntrackKey)
                log.debug("got new conntrack key: {}", k)
                conntrackTable.touch(k, ConnTrackState.RETURN_FLOW)
                flowInvalidation.invalidateFlowsFor(k)
            }

            val natEntries = state.getNatEntriesList.iterator
            while (natEntries.hasNext) {
                val nat = natEntries.next()
                val k = natKeyFromProto(nat.getK)
                val v = natBindingFromProto(nat.getV)
                log.debug("Got new nat mapping: {} -> {}", k, v)
                natTable.touch(k, v)
                flowInvalidation.invalidateFlowsFor(k)
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
                                    ports: JSet[UUID], tags: JSet[FlowTag]) {
        def addPeerFor(port: Port) {
            if ((port.hostId ne null) && (port.hostId != underlay.host.id))
                hosts.add(port.hostId)
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
    protected def resolvePeers(ingressPort: UUID,
                               egressPorts: JList[UUID],
                               hosts: JSet[UUID],
                               ports: JSet[UUID],
                               tags: JSet[FlowTag]): Unit = {
        hosts.clear()
        ports.clear()
        collectPeersForPort(ingressPort, hosts, ports, tags)
        val portsIt = egressPorts.iterator
        while (portsIt.hasNext) {
            val port = portsIt.next()
            ports.add(port)
            collectPeersForPort(port, hosts, ports, tags)
        }

        log.debug("Resolved peers {}", hosts)
    }
}

class FlowStateReplicator(
        conntrackTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
        natTable: FlowStateTable[NatKey, NatBinding],
        storage: FlowStateStorage,
        underlay: UnderlayResolver,
        flowInvalidation: FlowInvalidation,
        tso: Byte)(implicit as: ActorSystem)
        extends BaseFlowStateReplicator(conntrackTable, natTable, storage, underlay,
                                        flowInvalidation, tso) {

    override val log = Logger(LoggerFactory.getLogger("org.midonet.state.replication"))

    @throws(classOf[NotYetException])
    override def getPort(id: UUID) = VTA.tryAsk[Port](id)

    @throws(classOf[NotYetException])
    override def getPortGroup(id: UUID) = VTA.tryAsk[PortGroup](id)
}
