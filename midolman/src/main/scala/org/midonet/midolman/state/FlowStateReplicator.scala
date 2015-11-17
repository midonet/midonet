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

import java.util.{ArrayList, Collection, HashSet => JHashSet, Iterator => JIterator, Set => JSet, UUID}

import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.cluster.flowstate.proto.{FlowState => FlowStateSbe}
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.flows.FlowTagIndexer
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.{NotYetException, UnderlayResolver}
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions.setKey
import org.midonet.odp.flows.FlowKeys.tunnel
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.state.FlowStateTable
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent._
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
class FlowStateReplicator(
        conntrackTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
        natTable: FlowStateTable[NatKey, NatBinding],
        traceTable: FlowStateTable[TraceKey, TraceContext],
        storageFuture: Future[FlowStateStorage],
        hostId: UUID,
        peerResolver: PeerResolver,
        underlay: UnderlayResolver,
        flowInvalidation: FlowTagIndexer,
        tos: Byte) {
    import FlowStatePackets._

    private val log = Logger(LoggerFactory.getLogger("org.midonet.state.replication"))

    private val flowStateEncoder = new SbeEncoder

    /* Used for message building */
    private[this] var txIngressPort: UUID = _
    private[this] val txPeers: JSet[UUID] = new JHashSet[UUID]()
    private[this] val txPorts: JSet[UUID] = new JHashSet[UUID]()

    private[this] var storage: FlowStateStorage = _

    storageFuture.onSuccess { case s => storage = s }(ExecutionContext.callingThread)

    private val _conntrackAdder = new Reducer[ConnTrackKey, ConnTrackValue, ArrayList[Callback0]] {
        override def apply(callbacks: ArrayList[Callback0], k: ConnTrackKey,
                           v: ConnTrackValue): ArrayList[Callback0] = {
            log.debug("touch conntrack key: {}", k)
            if (storage ne null)
                storage.touchConnTrackKey(k, txIngressPort, txPorts.iterator())

            callbacks.add(new Callback0 {
                override def call(): Unit = conntrackTable.unref(k)
            })
            callbacks
        }
    }

    private val _conntrackEncoder = new Reducer[ConnTrackKey, ConnTrackValue,
                                                FlowStateSbe.Conntrack] {
        override def apply(conntrack: FlowStateSbe.Conntrack, k: ConnTrackKey,
                           v: ConnTrackValue): FlowStateSbe.Conntrack = {
            log.debug("push conntrack key: {}", k)
            connTrackKeyToSbe(k, conntrack.next())
            conntrack
        }
    }

    private val _natAdder = new Reducer[NatKey, NatBinding, ArrayList[Callback0]] {
        override def apply(callbacks: ArrayList[Callback0], k: NatKey,
                           v: NatBinding): ArrayList[Callback0] = {
            log.debug("touch nat key: {}", k)
            if (storage ne null)
                storage.touchNatKey(k, v, txIngressPort, txPorts.iterator())

            callbacks.add(new Callback0 {
                override def call(): Unit = natTable.unref(k)
            })
            callbacks
        }
    }

    private val _natEncoder = new Reducer[NatKey, NatBinding, FlowStateSbe.Nat] {
        override def apply(nat: FlowStateSbe.Nat, k: NatKey,
                           v: NatBinding): FlowStateSbe.Nat = {
            log.debug("push nat key: {}", k)
            natToSbe(k, v, nat.next())
            nat
        }
    }

    private def addTraceState(flowStateMessage: FlowStateSbe,
                              k: TraceKey, ctx: TraceContext): Unit = {
        log.debug("push trace key: {}", k)
        val trace = flowStateMessage.traceCount(1)

        traceToSbe(ctx.flowTraceId, k, trace.next())
        val requests = ctx.requests
        val traceIds = flowStateMessage.traceRequestIdsCount(requests.size)
        var i = 0
        while (i < requests.size) {
            uuidToSbe(requests.get(i), traceIds.next().id)
            i += 1
        }
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
            flowInvalidation.invalidateFlowsFor(k)
        }
    }

    private def importNat(entries: JIterator[java.util.Map.Entry[NatKey, NatBinding]]) {
        while (entries.hasNext) {
            val e = entries.next()
            log.debug("importing state key from storage: {}", e.getKey)
            natTable.putAndRef(e.getKey, e.getValue)
            natTable.unref(e.getKey)
            flowInvalidation.invalidateFlowsFor(e.getKey)
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
        val servicePorts = context.servicePorts
        resolvePeers(ingressPort, egressPorts, servicePorts,
                     txPeers, txPorts, context.flowTags)
        txIngressPort = ingressPort
        val callbacks = context.flowRemovedCallbacks

        context.conntrackTx.fold(callbacks, _conntrackAdder)
        context.natTx.fold(callbacks, _natAdder)

        if (!txPeers.isEmpty) {
            replicateFlowState(context)
        }
    }

    def replicateFlowState(context: PacketContext): Unit = {
        val flowStateMessage = flowStateEncoder.encodeTo(
            context.stateMessage)
        uuidToSbe(hostId, flowStateMessage.sender)

        context.conntrackTx.fold(
            flowStateMessage.conntrackCount(context.conntrackTx.size),
            _conntrackEncoder)
        context.natTx.fold(
            flowStateMessage.natCount(context.natTx.size),
            _natEncoder)
        if (context.tracingEnabled) {
            addTraceState(flowStateMessage,
                          context.traceKeyForEgress, context.traceContext)
        } else {
            flowStateMessage.traceCount(0)
        }

        context.stateMessageLength = flowStateEncoder.encodedLength
        hostsToActions(txPeers, context.stateActions)
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
        if (storage ne null)
            storage.submit()

    private def acceptNewState(msg: FlowStateSbe) {
        val conntrackIter = msg.conntrack
        while (conntrackIter.hasNext()) {
            val k = connTrackKeyFromSbe(conntrackIter.next())
            log.debug("got new conntrack key: {}", k)
            conntrackTable.touch(k, ConnTrackState.RETURN_FLOW)
            flowInvalidation.invalidateFlowsFor(k)
        }

        val natIter = msg.nat
        while (natIter.hasNext()) {
            val nat = natIter.next()
            val k = natKeyFromSbe(nat)
            val v = natBindingFromSbe(nat)
            log.debug("Got new nat mapping: {} -> {}", k, v)
            natTable.touch(k, v)
            flowInvalidation.invalidateFlowsFor(k)
        }

        val traceIter = msg.trace
        if (traceIter.hasNext()) {
            val trace = traceIter.next()
            val k = traceFromSbe(trace)
            val ctx = new TraceContext(uuidFromSbe(trace.flowTraceId))
            ctx.enable()

            val reqIdsIter = msg.traceRequestIds
            while (reqIdsIter.hasNext) {
                ctx.addRequest(uuidFromSbe(reqIdsIter.next().id))
            }
            log.debug("Got new trace state: {} -> {}", k, ctx)
            traceTable.touch(k, ctx)
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
    def accept(p: Ethernet) = {
        val data = parseDatagram(p)
        if (data == null) {
            log.info("Ignoring unexpected packet: {}", p)
        } else {
            try {
                val flowStateMessage = flowStateEncoder.decodeFrom(data.getData)
                val sender = uuidFromSbe(flowStateMessage.sender)

                log.debug("Got state replication message from: {}", sender)
                acceptNewState(flowStateMessage)
            } catch {
                case e: IllegalArgumentException =>
                    log.error("Error decoding flow state", e)
            }
        }
    }

    @throws(classOf[NotYetException])
    protected def resolvePeers(ingressPort: UUID,
                               egressPorts: ArrayList[UUID],
                               servicePorts: ArrayList[UUID],
                               hosts: JSet[UUID],
                               ports: JSet[UUID],
                               tags: Collection[FlowTag]): Unit = {
        hosts.clear()
        ports.clear()
        if (ingressPort ne null)
            peerResolver.collectPeersForPort(ingressPort, hosts, ports, tags)
        var i = 0
        while (i < egressPorts.size) {
            val port = egressPorts.get(i)
            ports.add(port)
            peerResolver.collectPeersForPort(port, hosts, ports, tags)
            i += 1
        }
        i = 0
        while (i < servicePorts.size) {
            val port = servicePorts.get(i)
            ports.add(port)
            peerResolver.collectPeersForPort(port, hosts, ports, tags)
            i += 1
        }
        log.debug("Resolved peers {}", hosts)
    }
}
