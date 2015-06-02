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
package org.midonet.midolman.routingprotocols

import java.util.UUID

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor._
import akka.pattern.gracefulStop

import com.google.inject.Inject

import org.midonet.cluster.data.{Converter, Route}
import org.midonet.cluster.data.storage.StateStorage
import org.midonet.cluster.models.Topology.BgpPeer
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.StatusKey
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.cluster.state.{LegacyStorage, LocalPortActive}
import org.midonet.cluster.{Client, DataClient}
import org.midonet.midolman.cluster.MidolmanActorsModule.ZEBRA_SERVER_LOOP
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingHandler.PortActive
import org.midonet.midolman.state.ZkConnectionAwareWatcher
import org.midonet.midolman.topology.{VirtualTopology, VirtualTopologyActor}
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.{DatapathState, Referenceable, SimulationBackChannel}
import org.midonet.util.concurrent.ReactiveActor
import org.midonet.util.concurrent.ReactiveActor.{OnError, OnCompleted}
import org.midonet.util.eventloop.SelectLoop
import org.midonet.util.reactivex._

object RoutingManagerActor extends Referenceable {
    override val Name = "RoutingManager"

    case class ShowBgp(port : UUID, cmd : String)
    case class BgpStatus(status : Array[String])

    private[routingprotocols] trait RoutingStorage {
        def setStatus(bgpId: UUID, status: String): Unit
        def addRoute(route: Route): UUID
        def removeRoute(route: Route): Unit
        def learnedRoutes(routerId: UUID, portId: UUID): Set[Route]
    }

    private[routingprotocols] class LegacyRoutingStorage(dataClient: DataClient)
        extends RoutingStorage {
        override def setStatus(bgpId: UUID, status: String): Unit = {
            dataClient.bgpSetStatus(bgpId, status)
        }
        override def addRoute(route: Route): UUID = {
            dataClient.routesCreateEphemeral(route)
        }
        override def removeRoute(route: Route): Unit = {
            dataClient.routesDelete(route.getId)
        }
        override def learnedRoutes(routerId: UUID, portId: UUID): Set[Route] = {
            dataClient.routesFindByRouter(routerId).asScala.filter { route =>
                route.isLearned && route.getNextHopPort == portId
            }.toSet
        }
    }

    private[routingprotocols] class NewRoutingStorage(storage: StateStorage)
        extends RoutingStorage {
        override def setStatus(bgpId: UUID, status: String): Unit = {
            storage.addValue(classOf[BgpPeer], bgpId, StatusKey, status)
                .await(5 seconds)
        }
        override def addRoute(route: Route): UUID = {
            storage.addRoute(Converter.toRouteConfig(route)).await(5 seconds)
            UUID.randomUUID()
        }
        override def removeRoute(route: Route): Unit = {
            storage.removeRoute(Converter.toRouteConfig(route)).await(5 seconds)
        }
        override def learnedRoutes(routerId: UUID, portId: UUID): Set[Route] = {
            storage.getPortRoutes(portId).await(5 seconds)
                .map(Converter.fromRouteConfig)
        }
    }

    private case class HandlerStop(portId: UUID, value: Boolean)
    private case class HandlerStopError(portId: UUID, e: Throwable)
}

class RoutingManagerActor extends ReactiveActor[AnyRef]
                          with ActorLogWithoutPath {
    import context.system
    import RoutingManagerActor._

    override def logSource = "org.midonet.routing.bgp"

    private implicit val ec: ExecutionContext = context.system.dispatcher

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    var dataClient: DataClient = null
    @Inject
    var config: MidolmanConfig = null
    @Inject
    var client: Client = null
    @Inject
    var legacyStorage: LegacyStorage = null
    @Inject
    var backend: MidonetBackend = null
    @Inject
    var zkConnWatcher: ZkConnectionAwareWatcher = null
    @Inject
    @ZEBRA_SERVER_LOOP
    var zebraLoop: SelectLoop = null
    @Inject
    var flowInvalidator: SimulationBackChannel = null
    @Inject
    var dpState: DatapathState = null
    var routingStorage: RoutingStorage = null

    private var bgpPortIdx = 0

    private val activePorts = mutable.Set[UUID]()
    private val portHandlers = mutable.Map[UUID, ActorRef]()
    private val portHandlerTimeout = 5 seconds

    @Inject
    var upcallConnManager: UpcallDatapathConnectionManager = null

    // Actor handler for the V1 stack.
    private val receiveV1: Actor.Receive = {
        case LocalPortActive(portID, true) =>
            log.debug(s"port $portID became active")
            if (!activePorts.contains(portID)) {
                activePorts.add(portID)
                // Request the port configuration
                VirtualTopologyActor ! PortRequest(portID)
            }
            portHandlers.get(portID) match {
                case None =>
                case Some(routingHandler) => routingHandler ! PortActive(true)
            }

        case LocalPortActive(portID, false) =>
            log.debug(s"port $portID became inactive")
            if (!activePorts.contains(portID)) {
                log.error("we should have had information about port {}",
                          portID)
            } else {
                activePorts.remove(portID)

                // Only exterior ports can have a routing handler
                val result = portHandlers.get(portID)
                result match {
                    case None =>
                    // FIXME(guillermo)
                    // * This stops the actor but does not remove it from
                    //   the portHandlers map. It will not be restarted
                    //   when the port becomes active again
                    // * The zk managers do not allow unsubscription or
                    //   multiple subscribers, thus stopping the actor
                    //   is not an option, the actor must be told about
                    //   the port status so it can stop BGPd while the
                    //   port is inactive and start it up again when
                    //   the port is up again.
                    //   (See: ClusterManager:L040)
                    case Some(routingHandler) =>
                        routingHandler ! PortActive(false)
                }
            }

        case port: RouterPort if !port.isInterior =>
            // Only exterior virtual router ports support BGP.
            // Create a handler if there isn't one and the port is active
            if (activePorts.contains(port.id))
                log.debug("RoutingManager - port is active: " + port.id)

            if (portHandlers.get(port.id).isEmpty)
                log.debug(s"no RoutingHandler is registered with port: ${port.id}")

            if (activePorts.contains(port.id)
                && portHandlers.get(port.id).isEmpty) {
                bgpPortIdx += 1

                // need to store index locally, as the props below closes over
                // it, possibly causing multiple bgp handlers to start with the
                // same index number
                val portIndexForHandler = bgpPortIdx
                portHandlers.put(
                    port.id,
                    context.actorOf(
                        Props(RoutingHandler(port, portIndexForHandler,
                                             flowInvalidator, dpState,
                                             upcallConnManager, client,
                                             routingStorage, config,
                                             zkConnWatcher, zebraLoop)).
                            withDispatcher("actors.pinned-dispatcher"),
                        name = port.id.toString)
                )
                log.debug(s"RoutingHandler creation requested for port ${port.id}")
            }

        case port: Port => // do nothing

        case _ => log.error("Unknown message.")
    }

    // Actor handler for the V2 stack.
    private val receiveV2: Actor.Receive = {
        case LocalPortActive(portId, true) =>
            log.debug("Port {} became active", portId)
            if (!activePorts.contains(portId)) {
                activePorts add portId
                // Subscribe to BGP port updates
                VirtualTopology.observable[BgpPort](portId).subscribe(this)
            }
            portHandlers get portId match {
                case Some(routingHandler) => routingHandler ! PortActive(true)
                case None =>
            }

        case LocalPortActive(portId, false) =>
            log.debug("Port {} became inactive", portId)
            if (activePorts remove portId) {
            } else {
                log.error("Port {} unknown", portId)
            }

        case BgpPort(port, _) if port.isExterior =>
            if (activePorts.contains(port.id) &&
                !portHandlers.contains(port.id)) {
                bgpPortIdx += 1
                val portIndexForHandler = bgpPortIdx

                log.debug(s"Creating routing handler for port {}", port.id)

                val portHandler = context.actorOf(
                    Props(RoutingHandler(port, portIndexForHandler,
                                         flowInvalidator, dpState,
                                         upcallConnManager, client,
                                         routingStorage, config, zkConnWatcher,
                                         zebraLoop)).
                        withDispatcher("actors.pinned-dispatcher"),
                    name = port.id.toString)
                portHandlers.put(port.id, portHandler)
            }

        case BgpPort(port, _) => // Ignore non-exterior router ports

        case OnCompleted => // Ignore completed notifications

        case OnError(BgpPortDeleted(portId)) =>
            log.debug("Port {} deleted", portId)
            stopHandler(portId)

        case OnError(BgpRouterDeleted(portId, routerId)) =>
            log.debug("Router {} for port {} deleted", routerId, portId)
            stopHandler(portId)

        case OnError(BgpPortError(portId, e)) =>
            log.error("Error on the update stream for port {}", portId, e)
            stopHandler(portId)

        case OnError(e) =>
            log.error("Unhandled exception on BGP port observable", e)

        case HandlerStop(portId, _) =>
            log.debug("Handler for port {} stopped successfully", portId)
            portHandlers remove portId

        case HandlerStopError(portId, e) =>
            log.error("Failed to stop routing handler for port {}", portId, e)
            portHandlers remove portId

        case _ => log.error("Unknown message")
    }

    override def preStart() {
        super.preStart()
        routingStorage =
            if (config.zookeeper.useNewStack)
                new NewRoutingStorage(backend.stateStore)
            else
                new LegacyRoutingStorage(dataClient)
        legacyStorage.localPortActiveObservable.subscribe(this)
    }

    override def receive = {
        if (config.zookeeper.useNewStack) receiveV2 else receiveV1
    }

    /** Stops the routing handler for the specified port identifier. Upon
      * completion of the handler termination, the actor will receive a
      * [[HandlerStop]] if the stop was successful, or [[HandlerStopError]]
      * otherwise. */
    private def stopHandler(portId: UUID): Unit = {
        portHandlers get portId match {
            case Some(routingHandler) =>
                gracefulStop(routingHandler, portHandlerTimeout) onComplete {
                    case Success(value) => self ! HandlerStop(portId, value)
                    case Failure(e) => self ! HandlerStopError(portId, e)
                }
            case None => // ignore
        }
    }

}
