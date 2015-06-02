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
import scala.concurrent.duration._

import akka.actor._

import com.google.inject.Inject

import org.midonet.cluster.data.{Converter, Route}
import org.midonet.cluster.data.storage.StateStorage
import org.midonet.cluster.models.Topology.Bgp
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
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.devices.{Port, RouterPort}
import org.midonet.midolman.{DatapathState, Referenceable, SimulationBackChannel}
import org.midonet.util.concurrent.ReactiveActor
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
    }

    private[routingprotocols] class NewRoutingStorage(storage: StateStorage)
        extends RoutingStorage {
        override def setStatus(bgpId: UUID, status: String): Unit = {
            storage.addValue(classOf[Bgp], bgpId, StatusKey, status)
                .await(5 seconds)
        }
        override def addRoute(route: Route): UUID = {
            storage.addRoute(Converter.toRouteConfig(route))
                .await(5 seconds)
            UUID.randomUUID()
        }
        override def removeRoute(route: Route): Unit = {
            storage.removeRoute(Converter.toRouteConfig(route))
                .await(5 seconds)
        }
    }

}

class RoutingManagerActor extends ReactiveActor[LocalPortActive]
                          with ActorLogWithoutPath {
    import context.system
    import RoutingManagerActor._

    override def logSource = "org.midonet.routing.bgp"

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

    @Inject
    var upcallConnManager: UpcallDatapathConnectionManager = null

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
                        Props(new RoutingHandler(port, portIndexForHandler,
                                    flowInvalidator, dpState, upcallConnManager,
                                    client, routingStorage, config, zkConnWatcher,
                                    zebraLoop)).
                              withDispatcher("actors.pinned-dispatcher"),
                        name = port.id.toString)
                )
                log.debug(s"RoutingHandler creation requested for port ${port.id}")
            }

        case port: Port => // do nothing

        case ShowBgp(portID : UUID, cmd : String) =>
            portHandlers.get(portID) match {
              case Some(handler) =>
                handler forward RoutingHandler.BGPD_SHOW(cmd)
              case None =>
                sender ! BgpStatus(Array[String](s"No BGP handler is on $portID"))
            }

        case _ => log.error("Unknown message.")
    }

}
