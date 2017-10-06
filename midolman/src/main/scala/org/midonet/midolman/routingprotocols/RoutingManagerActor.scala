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
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import com.google.inject.Inject

import rx.Subscription
import rx.subscriptions.CompositeSubscription

import org.midonet.cluster.backend.zookeeper.ZkConnectionProvider.BGP_ZK_INFRA
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkConnectionAwareWatcher, ZkConnectionProvider}
import org.midonet.cluster.data.storage.StateStorage
import org.midonet.cluster.models.Topology.{Port, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.BgpKey
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.containers.Containers
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingHandler.PortActive
import org.midonet.midolman.services.SelectLoopService.ZEBRA_SERVER_LOOP
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.{DatapathState, Referenceable, SimulationBackChannel}
import org.midonet.util.concurrent.ReactiveActor._
import org.midonet.util.concurrent.{ReactiveActor, toFutureOps}
import org.midonet.util.eventloop.{Reactor, SelectLoop}
import org.midonet.util.functors._
import org.midonet.util.reactivex._

object RoutingManagerActor extends Referenceable {
    override val Name = "RoutingManager"

    case class ShowBgp(port: UUID, cmd: String)
    case class BgpStatus(status: Array[String])
    case class BgpContainerReady(portId: UUID)
    case class StopBgpHandlers()

    private[routingprotocols] trait RoutingStorage {
        def setStatus(portId: UUID, status: String): Future[UUID]
        def addRoute(route: Route, portId: UUID): Future[Route]
        def removeRoute(route: Route, portId: UUID): Future[Route]
        def learnedRoutes(routerId: UUID, portId: UUID, hostId: UUID)
        : Future[Set[Route]]
    }

    private[routingprotocols] class RoutingStorageImpl(storage: StateStorage)
        extends RoutingStorage {
        override def setStatus(portId: UUID, status: String): Future[UUID] = {
            storage.addValue(classOf[Port], portId, BgpKey, status)
                   .map[UUID](makeFunc1(_ => portId))
                   .asFuture
        }
        override def addRoute(route: Route, portId: UUID): Future[Route] = {
            storage.addRoute(route, Some(portId))
                   .map[Route](makeFunc1(_ => route))
                   .asFuture
        }
        override def removeRoute(route: Route, portId: UUID): Future[Route] = {
            storage.removeRoute(route, Some(portId))
                   .map[Route](makeFunc1(_ => route))
                   .asFuture
        }
        override def learnedRoutes(routerId: UUID, portId: UUID, hostId: UUID)
        : Future[Set[Route]] = {
            storage.getPortRoutes(portId, hostId).asFuture
        }
    }

    private case class HandlerStop(portId: UUID, value: Boolean)
    private case class HandlerStopError(portId: UUID, e: Throwable)

    def selfRefFuture: Future[ActorRef] = {
        selfRefPromise.future
    }

    private val selfRefPromise = Promise[ActorRef]

}

class RoutingManagerActor extends ReactiveActor[AnyRef]
                          with ActorLogWithoutPath {
    import RoutingManagerActor._

    override def logSource = "org.midonet.routing.bgp"

    private implicit val ec: ExecutionContext = context.system.dispatcher

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    var zkConnectionProvider: ZkConnectionProvider = null

    @Inject
    var vt: VirtualTopology = null

    @Inject
    var config: MidolmanConfig = null

    @Inject
    var backend: MidonetBackend = null

    @Inject
    @BGP_ZK_INFRA
    var zkConnWatcher: ZkConnectionAwareWatcher = null

    @Inject
    @ZEBRA_SERVER_LOOP
    var zebraLoop: SelectLoop = null

    @Inject
    var flowInvalidator: SimulationBackChannel = null

    @Inject
    var dpState: DatapathState = null

    @Inject
    @BGP_ZK_INFRA
    var reactorBgp: Reactor = null

    var routingStorage: RoutingStorage = null

    var zkConnection: ZkConnection = null

    var bgpActorCount = 0

    private var bgpPortIdx = 0

    private val portsSubscription = new CompositeSubscription()

    private val activePorts = mutable.Map[UUID, Subscription]()
    private val portHandlers = mutable.Map[UUID, ActorRef]()

    // Pre-load with null == false so we don't have to check for null.
    private val containerIsQuagga = mutable.Map[UUID, Boolean]((null, false))

    @Inject
    var upcallConnManager: UpcallDatapathConnectionManager = null

    var actorsServiceSender: ActorRef = _

    implicit val timeout: Timeout = new Timeout(1 second)

    private def sendPortActive(portId: UUID) = {
        log.debug("Port {} became active", portId)
        if (!activePorts.contains(portId)) {
            // Subscribe to BGP port updates and add the subscription to
            // the active ports.
            activePorts += portId ->
                VirtualTopology.observable(classOf[BgpPort], portId)
                    .subscribe(this)
        }
        portHandlers get portId match {
            case Some(routingHandler) => routingHandler ! PortActive(true)
            case None =>
        }
    }

    def isQuaggaContainerPort(port: RouterPort): Boolean = {
        containerIsQuagga.getOrElseUpdate(port.containerId, {
            vt.store.get(classOf[ServiceContainer], port.containerId).await()
                .getServiceType == Containers.QUAGGA_CONTAINER
        })
    }

    def isPossibleBgpPort(port: RouterPort): Boolean = {
        (port.portAddress4 ne null) &&
        (port.isExterior || isQuaggaContainerPort(port))
    }

    def ensureZkConnection() = {
        if (bgpActorCount == 0) {
            zkConnection = zkConnectionProvider.get(zkConnWatcher, reactorBgp)
            log.info("Opening BGP ZK Connection")
        }
        bgpActorCount = bgpActorCount + 1
    }

    def checkZkConnection() = {
        if (bgpActorCount == 1) {
            zkConnection.close()
            log.info("Closing BGP ZK Connection")
        }
        bgpActorCount = bgpActorCount - 1
    }

    override def receive = {
        case BgpContainerReady(portId) => sendPortActive(portId)
        case LocalPortActive(portId, _, true) => sendPortActive(portId)

        case LocalPortActive(portId, _, false) =>
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

        case BgpPort(port, _, _) =>
            if (isPossibleBgpPort(port) &&
                activePorts.contains(port.id) &&
                !portHandlers.contains(port.id)) {

                bgpPortIdx += 1
                val portIndexForHandler = bgpPortIdx

                log.debug(s"Starting BGP routing for port {}", port.id)

                ensureZkConnection()

                val portHandler = context.actorOf(
                    Props(RoutingHandler(port, portIndexForHandler,
                                         flowInvalidator, dpState,
                                         upcallConnManager, routingStorage,
                                         config, zkConnWatcher, zebraLoop,
                                         vt, isQuaggaContainerPort(port))).
                        withDispatcher("actors.pinned-dispatcher"),
                    name = s"bgp:${port.id}:$bgpPortIdx")
                portHandlers.put(port.id, portHandler)
            }

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

        case RoutingHandlerStopped(id) =>
            log.debug(s"BGP routing handler for port $id successfully stopped.")
            checkZkConnection()

        case StopBgpHandlers() =>
            log.debug("Stopping all BGP handler actors ...")
            stopAllHandlers()
            context become stopping

        case _ => log.error("Unknown message")
    }

    private def stopping: Actor.Receive = {
        case RoutingHandlerStopped(id) =>
            log.debug(s"BGP routing handler for port $id successfully stopped.")
            checkZkConnection()
            if (bgpActorCount == 0) {
                log.debug("ALL BGP routing handlers stopped. " +
                          "Notify MidolmanActors service.")
                actorsServiceSender ! RoutingManagerStopped
            }

        case unhandled =>
            log.debug(s"Actor stopping, ignoring message: $unhandled")
    }

    override def preStart(): Unit = {
        super.preStart()
        selfRefPromise trySuccess self
        routingStorage = new RoutingStorageImpl(backend.stateStore)

        portsSubscription add VirtualToPhysicalMapper.portsActive.subscribe(this)
    }

    override def postStop(): Unit = {
        portsSubscription.unsubscribe()
    }

    private def stopAllHandlers(): Unit = {
        if (bgpActorCount == 0) {
            log.debug("No BGP routing handlers to stop. " +
                      "Notify MidolmanActors service")
            sender ! RoutingManagerStopped
        } else {
            portHandlers.keys foreach stopHandler
            actorsServiceSender = sender()
            log.debug("Waiting for routing handlers to report FIN ACK.")
        }
    }

    /** Stops the routing handler for the specified port identifier. Upon
      * completion of the handler termination, the actor will receive a
      * [[HandlerStop]] if the stop was successful, or [[HandlerStopError]]
      * otherwise. */
    private def stopHandler(portId: UUID): Unit = {
        portHandlers remove portId match {
            case Some(routingHandler) =>
                log.debug("Stopping BGP routing for port {}", portId)
                routingHandler ? StopRoutingHandler pipeTo self
            case None => // ignore
        }
    }

}
