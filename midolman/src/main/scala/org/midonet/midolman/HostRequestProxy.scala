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
package org.midonet.midolman

import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.{UUID, HashMap => JHashMap, HashSet => JHashSet, Map => JMap, Set => JSet}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

import akka.actor.ActorRef

import rx.Subscription

import org.midonet.cluster.storage.FlowStateStorage
import org.midonet.midolman.SimulationBackChannel.{BackChannelMessage, Broadcast}
import org.midonet.midolman.config.FlowStateConfig
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState._
import org.midonet.midolman.topology.devices.{Host => DevicesHost}
import org.midonet.midolman.topology.rcu.{PortBinding, ResolvedHost}
import org.midonet.midolman.topology.{VirtualTopology, VirtualToPhysicalMapper => VTPM}
import org.midonet.packets.IPv4Addr
import org.midonet.packets.NatState.NatBinding
import org.midonet.services.flowstate.transfer.client.FlowStateInternalClient
import org.midonet.util.concurrent.ReactiveActor.{OnCompleted, OnError}
import org.midonet.util.concurrent._

object HostRequestProxy {

    case class FlowStateBatch(
            strongConnTrack: JSet[ConnTrackKey],
            weakConnTrack: JSet[ConnTrackKey],
            strongNat: JMap[NatKey, NatBinding],
            weakNat: JMap[NatKey, NatBinding])
            extends BackChannelMessage with Broadcast {

        def merge(other: FlowStateBatch): FlowStateBatch = {
            strongConnTrack.addAll(other.strongConnTrack)
            weakConnTrack.addAll(other.strongConnTrack)
            strongNat.putAll(other.strongNat)
            weakNat.putAll(other.weakNat)
            this
        }

        def size() =
            strongConnTrack.size() +
            weakConnTrack.size() +
            strongNat.size() +
            weakNat.size()
    }

    val EmptyFlowStateBatch = FlowStateBatch(new JHashSet[ConnTrackKey](),
                                             new JHashSet[ConnTrackKey](),
                                             new JHashMap[NatKey, NatBinding](),
                                             new JHashMap[NatKey, NatBinding]())
}


/**
  * This actor creates a host subscription in the VTPM on behalf of another
  * subscriber. It will proxy requests making sure per-flow state for the
  * host's ports is fetched from Cassandra before the subscriber receives
  * the host object.
  */
class HostRequestProxy(hostId: UUID,
                       backChannel: SimulationBackChannel,
                       storageFuture: Future[FlowStateStorage[ConnTrackKey, NatKey]],
                       subscriber: ActorRef,
                       underlayResolver: UnderlayResolver,
                       flowStateConfig: FlowStateConfig) extends ReactiveActor[DevicesHost]
                                                         with ActorLogWithoutPath
                                                         with SingleThreadExecutionContextProvider {

    override def logSource = "org.midonet.datapath-control.host-proxy"

    import org.midonet.midolman.HostRequestProxy._

    import context.{dispatcher, system}

    case object ReSync

    var lastPorts: Set[UUID] = Set.empty
    val belt = new ConveyorBelt(_ => {})
    private var subscription: Subscription = null

    private val tcpClientExecutionContext =
        if (flowStateConfig.localReadState) {
            ExecutionContext.fromExecutor(newSingleThreadExecutor)
        } else {
            null
        }

    private val tcpClient: FlowStateInternalClient =
        if (flowStateConfig.localReadState) {
            new FlowStateInternalClient(flowStateConfig)
        } else {
            null
        }

    override def preStart(): Unit = {
        VTPM.hosts(hostId).subscribe(this)
    }

    override def postStop(): Unit = {
        if (subscription ne null) {
            subscription.unsubscribe()
            subscription = null
        }
    }

    private def stateForPort(storage: FlowStateStorage[ConnTrackKey, NatKey],
                             portInfo: (UUID, UUID)): Future[FlowStateBatch] = {
        val (portId, previousOwnerId) = portInfo

        if (flowStateConfig.legacyReadState) {
            legacyStateRequestForPort(storage, portId)
        } else if (flowStateConfig.localReadState) {
            stateRequestForPort(portId, previousOwnerId)
        } else {
            log warn "Flow state not being recovered from local nor legacy " +
                "storage. Check the agent's configuration to turn on one of " +
                "the flags for flow state recovery"
            Future.successful(EmptyFlowStateBatch)
        }
    }

    private def legacyStateRequestForPort(storage: FlowStateStorage[ConnTrackKey, NatKey],
                                  port: UUID): Future[FlowStateBatch] = {
        val scf = storage.fetchStrongConnTrackRefs(port)
        val wcf = storage.fetchWeakConnTrackRefs(port)
        val snf = storage.fetchStrongNatRefs(port)
        val wnf = storage.fetchWeakNatRefs(port)

        ((scf zip wcf) zip (snf zip wnf)) map {
            case ((sc, wc), (sn, wn)) => FlowStateBatch(sc, wc, sn, wn)
        }
    }

    private def stateRequestForPort(port: UUID, previousOwnerId: UUID): Future[FlowStateBatch] = {
        if (previousOwnerId eq null) {
            // First binding -> no flow state to recover
            Future.successful(EmptyFlowStateBatch)
        } else {
            Future {
                if (previousOwnerId == hostId) {
                    log debug s"Requesting local flow state for port: $port"
                    tcpClient.internalFlowStateFrom(port)
                } else {
                    log debug s"Requesting remote flow state for port: $port"
                    val ip = resolveHostIp(previousOwnerId)

                    ip match {
                        case Some(hostIp) =>
                            tcpClient.remoteFlowStateFrom(hostIp, port)
                        case None =>
                            log.debug(
                                s"Host $previousOwnerId is not registered in" +
                                " any tunnel zone when trying to fetch flow state from it.")
                            EmptyFlowStateBatch
                    }
                }
            }(tcpClientExecutionContext)
        }
    }

    private def stateForPorts(storage: FlowStateStorage[ConnTrackKey, NatKey],
                              bindings: Map[UUID, UUID]): Future[FlowStateBatch] =
        Future.fold(bindings map (stateForPort(storage, _)))(EmptyFlowStateBatch) {
            (batch: FlowStateBatch, v: FlowStateBatch) => batch.merge(v)
        }

    private def resolveHostIp(id: UUID) =
        underlayResolver.peerTunnelInfo(id)
                        .map(route => IPv4Addr.intToString(route.dstIp))

    /* Resolve all ports into UUIDs, creating a ResolvedHost object.
     *
     * Ports that failed to be fetched are filtered out. If any of them
     * is left out, we schedule a resync with the virtual to physical mapper,
     * effectively creating an in-band retry loop that will use the most
     * up to date version of the Host object.
     */
    private def resolvePorts(host: DevicesHost): ResolvedHost = {
        val futures = mutable.ArrayBuffer[Future[Any]]()
        val bindings = host.portBindings.map {
            case (id, (iface, previousHost)) =>
                try {
                    val port = VirtualTopology.tryGet(classOf[Port], id)
                    if (iface ne null)
                        Some(PortBinding(id, previousHost, port.tunnelKey, iface))
                    else
                        None
                } catch {
                    case NotYetException(f, _) =>
                        futures += f
                        None
                }
            }.collect { case Some(binding) => (binding.portId, binding) }

        if (futures.nonEmpty) {
            Future.sequence(futures).onComplete { case _ =>
                log.debug("Ports resolved, re-syncing")
                self ! ReSync
            }
        }

        ResolvedHost(host.id, host.alive, bindings.toMap, host.tunnelZones)
    }

    override def receive = super.receive orElse {
        case ReSync =>
            log.debug("Re-syncing port bindings")
            if (subscription ne null) {
                subscription.unsubscribe()
            }
            subscription = VTPM.hosts(hostId).subscribe(this)

        case h: DevicesHost =>
            log.debug("Received host update with bindings {}", h.portBindings)
            val resolved = resolvePorts(h)
            log.debug(s"Resolved host bindings to ${resolved.ports}")
            subscriber ! resolved
            belt.handle(() => {
                val ports = for ((id, (_, previousHost)) <- h.portBindings if !lastPorts.contains(id))
                    yield id -> previousHost

                lastPorts = h.portBindings.keySet

                storageFuture.flatMap(stateForPorts(_, ports)).andThen {
                    case Success(stateBatch) =>
                        log.debug(s"Fetched ${stateBatch.size()} pieces of " +
                                  s"flow state for ports ${ports.keySet}")
                        backChannel tell stateBatch
                    case Failure(e) =>
                        log.warn("Failed to fetch state", e)
                }(singleThreadExecutionContext)
            })

        case OnCompleted =>
            log.warn("Host deleted in storage: this is unexpected and the " +
                     "agent will stop processing host updates")

        case OnError(e) =>
            log.error("Host emitted error: this is unexpected and the agent" +
                      "will stop processing host updates", e)
    }
}
