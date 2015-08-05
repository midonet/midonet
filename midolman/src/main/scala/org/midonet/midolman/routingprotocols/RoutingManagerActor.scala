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

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import akka.actor._

import com.google.inject.Inject

import rx.Subscription

import org.midonet.cluster.data.storage.StateStorage
import org.midonet.cluster.data.{Converter, Route}
import org.midonet.cluster.models.Topology.BgpPeer
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.BgpKey
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.cluster.state.{LegacyStorage, LocalPortActive}
import org.midonet.cluster.{Client, DataClient}
import org.midonet.midolman.cluster.MidolmanActorsModule.ZEBRA_SERVER_LOOP
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingHandler.PortActive
import org.midonet.midolman.simulation.{Port, RouterPort}
import org.midonet.midolman.state.ZkConnectionAwareWatcher
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.topology.{VirtualTopology, VirtualTopologyActor}
import org.midonet.midolman.{DatapathState, Referenceable, SimulationBackChannel}
import org.midonet.util.concurrent.ReactiveActor
import org.midonet.util.concurrent.ReactiveActor.{OnCompleted, OnError}
import org.midonet.util.eventloop.SelectLoop
import org.midonet.util.functors._
import org.midonet.util.reactivex._

object RoutingManagerActor extends Referenceable {
    override val Name = "RoutingManager"

    case class ShowBgp(port : UUID, cmd : String)
    case class BgpStatus(status : Array[String])

    private[routingprotocols] trait RoutingStorage {
        def setStatus(bgpId: UUID, status: String): Future[UUID]
        def addRoute(route: Route): Future[Route]
        def removeRoute(route: Route): Future[Route]
        def learnedRoutes(routerId: UUID, portId: UUID): Future[Set[Route]]
    }

    private[routingprotocols] class RoutingStorageForV1(dataClient: DataClient)
        extends RoutingStorage {
        override def setStatus(bgpId: UUID, status: String): Future[UUID] = {
            Future.fromTry(Try {
                dataClient.bgpSetStatus(bgpId, status)
                bgpId
            })
        }
        override def addRoute(route: Route): Future[Route] = {
            Future.fromTry(Try {
                route.setId(dataClient.routesCreateEphemeral(route))
                route
            })
        }
        override def removeRoute(route: Route): Future[Route] = {
            Future.fromTry(Try {
                dataClient.routesDelete(route.getId)
                route
            })
        }
        override def learnedRoutes(routerId: UUID, portId: UUID)
        : Future[Set[Route]] = {
            Future.fromTry(Try {
                dataClient.routesFindByRouter(routerId).asScala.filter { route =>
                    route.isLearned && route.getNextHopPort == portId
                }.toSet
            })
        }
    }

    private[routingprotocols] class RoutingStorageForV2(storage: StateStorage)
        extends RoutingStorage {
        override def setStatus(bgpId: UUID, status: String): Future[UUID] = {
            storage.addValue(classOf[BgpPeer], bgpId, BgpKey, status)
                   .map[UUID](makeFunc1(_ => bgpId))
                   .asFuture
        }
        override def addRoute(route: Route): Future[Route] = {
            storage.addRoute(Converter.toRouteConfig(route))
                   .map[Route](makeFunc1(_ => route))
                   .asFuture
        }
        override def removeRoute(route: Route): Future[Route] = {
            storage.removeRoute(Converter.toRouteConfig(route))
                   .map[Route](makeFunc1(_ => route))
                   .asFuture
        }
        override def learnedRoutes(routerId: UUID, portId: UUID)
        : Future[Set[Route]] = {
            storage.getPortRoutes(portId)
                   .map[Set[Route]](makeFunc1(_.map(Converter.fromRouteConfig)))
                   .asFuture
        }
    }

    private case class HandlerStop(portId: UUID, value: Boolean)
    private case class HandlerStopError(portId: UUID, e: Throwable)
}

class RoutingManagerActor extends ReactiveActor[AnyRef]
                          with ActorLogWithoutPath {
    import RoutingManagerActor._

    import context.system

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

    private val activePorts = mutable.Map[UUID, Subscription]()
    private val portHandlers = mutable.Map[UUID, ActorRef]()

    @Inject
    var upcallConnManager: UpcallDatapathConnectionManager = null

    // Actor handler for the V1 stack.
    private val receiveV1: Actor.Receive = {
        case LocalPortActive(portID, true) =>
            log.debug(s"port $portID became active")
            if (!activePorts.contains(portID)) {
                activePorts += portID -> null
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
                // Subscribe to BGP port updates and add the subscription to
                // the active ports.
                activePorts += portId ->
                               VirtualTopology.observable[BgpPort](portId)
                                              .subscribe(this)
            }
            portHandlers get portId match {
                case Some(routingHandler) => routingHandler ! PortActive(true)
                case None =>
            }

        case LocalPortActive(portId, false) =>
            log.debug("Port {} became inactive", portId)
            activePorts remove portId match {
                case Some(subscription) =>
                    // Unsubscribing from BGP port updates and stop the port
                    // handler.
                    subscription.unsubscribe()
                    stopHandler(portId)
                case None =>
                    log.error("Port {} unknown", portId)
            }

        case BgpPort(port,_,_) if port.isExterior =>
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

        case BgpPort(_,_,_) => // Ignore non-exterior router ports

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

        case _ => log.error("Unknown message")
    }

    override def preStart() {
        super.preStart()
        routingStorage =
            if (config.zookeeper.useNewStack)
                new RoutingStorageForV2(backend.stateStore)
            else
                new RoutingStorageForV1(dataClient)
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
        portHandlers remove portId match {
            case Some(routingHandler) =>
                log.debug("Stopping port handler for port {}", portId)
                context stop routingHandler
            case None => // ignore
        }
    }

}
