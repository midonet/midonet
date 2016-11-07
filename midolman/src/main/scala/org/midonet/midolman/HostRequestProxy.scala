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

import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import java.nio.ByteBuffer
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.{UUID, HashMap => JHashMap, HashSet => JHashSet, Map => JMap, Set => JSet}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import akka.actor.ActorRef

import rx.Subscription

import org.midonet.cluster.storage.FlowStateStorage
import org.midonet.midolman.SimulationBackChannel.{BackChannelMessage, Broadcast}
import org.midonet.midolman.config.FlowStateConfig
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState._
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.topology.{VirtualToPhysicalMapper => VTPM}
import org.midonet.packets.IPv4Addr
import org.midonet.packets.NatState.NatBinding
import org.midonet.services.flowstate.transfer.client.FlowStateInternalClient
import org.midonet.services.flowstate.{FlowStateInternalMessageHeaderSize, FlowStateInternalMessageType, MaxMessageSize, MaxPortIds}
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

    def EmptyFlowStateBatch = FlowStateBatch(new JHashSet[ConnTrackKey](),
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
                       flowStateConfig: FlowStateConfig)
    extends ReactiveActor[Host] with ActorLogWithoutPath
    with SingleThreadExecutionContextProvider {

    override def logSource = "org.midonet.datapath-control.host-proxy"

    import org.midonet.midolman.HostRequestProxy._

    import context.{dispatcher, system}

    case object ReSync

    private var lastPorts: Set[UUID] = Set.empty
    private val belt = new ConveyorBelt(_ => {})
    private var subscription: Subscription = null

    private val tcpClientExecutionContext =
        ExecutionContext.fromExecutor(newSingleThreadExecutor)

    private val tcpClient: FlowStateInternalClient =
        new FlowStateInternalClient(flowStateConfig)

    override def preStart(): Unit = {
        VTPM.hosts(hostId).subscribe(this)
    }

    override def postStop(): Unit = {
        if (subscription ne null) {
            subscription.unsubscribe()
            subscription = null
        }
    }

    /* Used for sending flow state messages to minion. TODO: do a common
       frontend for udp and tcp messages sent to the minion */
    private val flowStateSocket = new DatagramSocket()
    private val flowStatePacket =
        new DatagramPacket(Array.emptyByteArray, 0,
                           InetAddress.getLoopbackAddress, flowStateConfig.port)
    private val flowStateBuffer = ByteBuffer.allocate(MaxMessageSize)

    private def requestLegacyStateForPort(portInfo: (UUID, UUID)): Future[FlowStateBatch] =
        storageFuture.flatMap { storage =>
            val (port, _) = portInfo

            val scf = storage.fetchStrongConnTrackRefs(port)
            val wcf = storage.fetchWeakConnTrackRefs(port)
            val snf = storage.fetchStrongNatRefs(port)
            val wnf = storage.fetchWeakNatRefs(port)

            ((scf zip wcf) zip (snf zip wnf)) map {
                case ((sc, wc), (sn, wn)) => FlowStateBatch(sc, wc, sn, wn)
            }
        }

    private def requestStateForPort(portInfo: (UUID, UUID)): Future[FlowStateBatch] = {
        val (port, previousOwnerId) = portInfo

        Future {
            if (previousOwnerId == null || previousOwnerId == hostId) {
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

    private def stateForPorts(bindings: Map[UUID, UUID],
                              request: ((UUID, UUID)) => Future[FlowStateBatch],
                              source: String): Future[FlowStateBatch] =
        mergedBatches(bindings map request)
            .andThen {
                case Success(stateBatch) =>
                    log.debug(s"Fetched ${stateBatch.size()} pieces of " +
                              s"flow state for ports ${bindings.keySet} " +
                              s"from $source")
                    backChannel tell stateBatch
                case Failure(e) =>
                    log.warn(s"Failed to fetch state from $source", e)
            }(singleThreadExecutionContext)

    private def mergedBatches(batches: Iterable[Future[FlowStateBatch]]) =
        Future.fold(batches)(EmptyFlowStateBatch) {
            (left: FlowStateBatch, right: FlowStateBatch) => left.merge(right)
        }

    private def resolveHostIp(id: UUID) =
        underlayResolver.peerTunnelInfo(id)
                        .map(route => IPv4Addr.intToString(route.dstIp))

    def updateOwnedPorts(portIds: Set[UUID]): Unit = {
        flowStateBuffer.clear()
        val size = Math.min(MaxPortIds, portIds.size)
        flowStateBuffer.putInt(FlowStateInternalMessageType.OwnedPortsUpdate)
        flowStateBuffer.putInt(size * 16) // UUID size = 16 bytes
        for (portId <- portIds) {
            flowStateBuffer.putLong(portId.getMostSignificantBits)
            flowStateBuffer.putLong(portId.getLeastSignificantBits)
        }
        flowStatePacket.setData(flowStateBuffer.array,
                                0,
                                size * 16 + FlowStateInternalMessageHeaderSize)
        flowStateSocket.send(flowStatePacket)
    }

    override def receive = super.receive orElse {
        case ReSync =>
            log.debug("Re-syncing port bindings")
            if (subscription ne null) {
                subscription.unsubscribe()
            }
            subscription = VTPM.hosts(hostId).subscribe(this)

        case host: Host =>
            log.debug("Received host update {}", host)
            subscriber ! host
            updateOwnedPorts(host.portBindings.keySet)
            belt.handle(() => {
                val ports = for ((id, binding) <- host.portBindings if !lastPorts.contains(id))
                    yield id -> binding.previousHostId

                lastPorts = host.portBindings.keySet
                Future.sequence(Seq(
                    stateForPorts(ports, requestLegacyStateForPort,
                                  "legacy storage (Cassandra)"),
                    stateForPorts(ports, requestStateForPort,
                                  "local storage")))
            })

        case OnCompleted =>
            log.warn("Host deleted in storage: this is unexpected and the " +
                     "agent will stop processing host updates")

        case OnError(e) =>
            log.error("Host emitted error: this is unexpected and the agent" +
                      "will stop processing host updates", e)
    }
}
