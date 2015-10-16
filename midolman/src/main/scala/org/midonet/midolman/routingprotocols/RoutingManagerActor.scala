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
import scala.concurrent.{ExecutionContext, Future}

import akka.actor._
import com.google.inject.Inject
import rx.Subscription

import org.midonet.cluster.data.storage.StateStorage
import org.midonet.cluster.data.Route
import org.midonet.cluster.models.Topology.{Port, BgpPeer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.BgpKey
import org.midonet.cluster.state.LegacyStorage
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingHandler.PortActive
import org.midonet.midolman.services.SelectLoopService.ZEBRA_SERVER_LOOP
import org.midonet.midolman.state.ZkConnectionAwareWatcher
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.topology.{Converter, LocalPortActive, VirtualToPhysicalMapper, VirtualTopology}
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
        def setStatus(portId: UUID, status: String): Future[UUID]
        def addRoute(route: Route): Future[Route]
        def removeRoute(route: Route): Future[Route]
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
        override def learnedRoutes(routerId: UUID, portId: UUID, hostId: UUID)
        : Future[Set[Route]] = {
            storage.getPortRoutes(portId, hostId)
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
    var config: MidolmanConfig = null
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

    override def receive = {
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
                                         upcallConnManager,
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
        routingStorage = new RoutingStorageImpl(backend.stateStore)

        VirtualToPhysicalMapper.localPortActiveObservable.subscribe(this)
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
