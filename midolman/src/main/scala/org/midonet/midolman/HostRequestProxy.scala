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

import java.util.{UUID, HashMap => JHashMap, HashSet => JHashSet, Map => JMap, Set => JSet}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import akka.actor.{Actor, ActorRef}

import org.midonet.midolman.SimulationBackChannel.{BackChannelMessage, Broadcast}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation.DeviceDeletedException
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.FlowStateStorage
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{HostRequest, HostUnsubscribe}
import org.midonet.midolman.topology.devices.{Port, Host => DevicesHost}
import org.midonet.midolman.topology.rcu.{PortBinding, ResolvedHost}
import org.midonet.midolman.topology.{VirtualToPhysicalMapper => VTPM, VirtualTopologyActor => VTA}
import org.midonet.util.concurrent._

object HostRequestProxy {
    case class FlowStateBatch(strongConnTrack: JSet[ConnTrackKey],
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

    def EmptyFlowStateBatch() = FlowStateBatch(new JHashSet[ConnTrackKey](),
                                               new JHashSet[ConnTrackKey](),
                                               new JHashMap[NatKey, NatBinding](),
                                               new JHashMap[NatKey, NatBinding]())

    case class PortSuccess(portId: UUID, port: Port)
    case class PortFailure(portId: UUID, t: Throwable)
    case class PortDeleted(portId: UUID, t: Throwable)
}


/**
  * This actor creates a host subscription in the VTPM on behalf of another
  * subscriber. It will proxy requests making sure per-flow state for the
  * host's ports is fetched from Cassandra before the subscriber receives
  * the host object.
  */
class HostRequestProxy(val hostId: UUID,
                       backChannel: SimulationBackChannel,
                       val storageFuture: Future[FlowStateStorage],
                       val subscriber: ActorRef)
        extends Actor
        with ActorLogWithoutPath
        with SingleThreadExecutionContextProvider {

    override def logSource = "org.midonet.datapath-control.host-proxy"

    import context.{dispatcher, system}
    import org.midonet.midolman.HostRequestProxy._

    private var lastPorts: Set[UUID] = Set.empty
    private val belt = new ConveyorBelt(_ => {})

    private var host: DevicesHost = null
    private val bindings = new mutable.HashMap[UUID, Try[Option[PortBinding]]]

    override def preStart(): Unit = {
        VTPM ! HostRequest(hostId)
    }

    override def postStop(): Unit = {
        VTPM ! HostUnsubscribe(hostId)
    }

    private def stateForPort(storage: FlowStateStorage,
                             port: UUID): Future[FlowStateBatch] = {
        val scf = storage.fetchStrongConnTrackRefs(port)
        val wcf = storage.fetchWeakConnTrackRefs(port)
        val snf = storage.fetchStrongNatRefs(port)
        val wnf = storage.fetchWeakNatRefs(port)

        ((scf zip wcf) zip (snf zip wnf)) map {
            case ((sc, wc) , (sn, wn)) => FlowStateBatch(sc, wc, sn, wn)
        }
    }

    private def stateForPorts(storage: FlowStateStorage,
                              ports: Iterable[UUID]): Future[FlowStateBatch] =
        Future.fold(ports map (stateForPort(storage, _)))(EmptyFlowStateBatch()) {
            (batch: FlowStateBatch, v: FlowStateBatch) => batch.merge(v)
        }

    /** Resolves the port bindings for the specified host into a
      * [[ResolvedHost]] object, notifies the subscribe and asynchronously
      * fetches the flow state for the resolved hosts.
      */
    private def resolveHost(h: DevicesHost): Unit = {
        val resolved = resolvePorts(h)
        log debug s"Resolved host bindings to ${resolved.ports}"
        subscriber ! resolved
        belt.handle(() => {
            val ps = h.portBindings.keySet -- lastPorts
            lastPorts = h.portBindings.keySet
            storageFuture.flatMap(stateForPorts(_, ps)).andThen {
                case Success(stateBatch) =>
                    log.debug(s"Fetched ${stateBatch.size()} pieces of flow " +
                              s"state for ports $ps")
                    backChannel.tell(stateBatch)
                case Failure(e) =>
                    log.warn("Failed to fetch state", e)
            }(singleThreadExecutionContext)
        })
    }

    /** Resolve the host ports into UUIDs, creating a [[ResolvedHost]] object.
      *
      * The method uses the `bindings` local map to track the ports that have
      * already been requested or that failed to be loaded, and those are
      * ignored.
      *
      * The ports that can be fetched from the topology immediately are used to
      * create the [[ResolvedHost]] object. The ports that failed to be fetched
      * are filtered out, and upon completion of their individual future, the
      * actor sends itself a message to either (i) resolve the host again if
      * the port was loaded successfully, or (ii) mark the port as failed and
      * log a warning if the loading failed.
      */
    private def resolvePorts(h: DevicesHost): ResolvedHost = {

        val futures = mutable.ArrayBuffer[Future[Any]]()

        def loadPort(portId: UUID, interfaceName: String): Unit = {
            try {
                val port = VTA.tryAsk[Port](portId)
                if (interfaceName ne null) {
                    bindings.put(portId,
                                 Success(Some(PortBinding(
                                     portId, interfaceName, port.tunnelKey))))
                } else {
                    bindings.put(portId, Success(None))
                }
            } catch {
                case nye: NotYetException =>
                    bindings.put(portId, Failure(nye))
                    futures += nye.waitFor andThen {
                        case Success(port: Port) =>
                            self ! PortSuccess(portId, port)
                        case Failure(e: DeviceDeletedException) =>
                            self ! PortDeleted(portId, e)
                        case Failure(e) =>
                            self ! PortFailure(portId, e)
                    }
            }
        }

        // Remove the port bindings no longer part of the current host.
        for (portId <- bindings.keySet if !host.portBindings.contains(portId)) {
            bindings remove portId
        }

        // Add the new port bindings for this host, except those that have
        // failed to load or are still pending.
        for ((portId, interfaceName) <- host.portBindings) {
            bindings get portId match {
                case Some(Failure(_)) =>
                    // Skip the ports that previously failed to load or are
                    // still pending with Failure(NotYetException).
                case _ =>
                    // Reload all other ports
                    loadPort(portId, interfaceName)
            }
        }

        val resolvedBindings = bindings collect {
            case (portId, Success(Some(binding))) => (portId, binding)
        } toMap

        ResolvedHost(host.id, host.alive, resolvedBindings, host.tunnelZones)
    }

    override def receive = super.receive orElse {
        case h: DevicesHost if h.id == hostId =>
            log info s"Host ${h.id} updated with bindings ${h.portBindings}"
            host = h
            resolveHost(h)

        case PortSuccess(portId, port) if bindings.contains(portId) =>
            log debug s"Loaded host binding port $portId"
            // Remove the port from the bindings map and call `resolveHosts`
            // again to load the ports of the current host from the VTA.
            bindings.remove(portId)
            resolveHost(host)

        case PortDeleted(portId, e) if bindings.contains(portId) =>
            log info s"Host binding port $portId was deleted"
            // Mark the port as failed: it will be not retried for the current
            // host.
            bindings.put(portId, Failure(e))

        case PortFailure(portId, e) if bindings.contains(portId) =>
            log.info(s"Failed to load host binding port $portId", e)
            // Remove the port from the bindings map and call `resolveHosts`
            // again to load the ports of the current host from the VTA.
            bindings.remove(portId)
            resolveHost(host)

        case message =>
            // Ignore message for different hosts and ports that are no longer
            // in the bindings list.
            log debug s"Unhandled message $message"
    }
}
