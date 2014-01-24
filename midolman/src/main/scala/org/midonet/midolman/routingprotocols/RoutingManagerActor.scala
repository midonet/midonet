/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.routingprotocols

import collection.mutable
import akka.actor._
import com.google.inject.Inject
import java.util.UUID

import org.midonet.cluster.{Client, DataClient}
import org.midonet.cluster.client.{Port, RouterPort}
import org.midonet.midolman.Referenceable
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingHandler.PortActive
import org.midonet.midolman.state.ZkConnectionAwareWatcher
import org.midonet.midolman.guice.reactor.ReactorModule
import org.midonet.util.eventloop.SelectLoop
import org.midonet.util.functors.Callback2

object RoutingManagerActor extends Referenceable {
    override val Name = "RoutingManager"
}

class RoutingManagerActor extends Actor with ActorLogWithoutPath {
    import context.system

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    var dataClient: DataClient = null
    @Inject
    var config: MidolmanConfig = null
    @Inject
    val client: Client = null
    @Inject
    var zkConnWatcher: ZkConnectionAwareWatcher = null
    @Inject
    @ReactorModule.ZEBRA_SERVER_LOOP
    var zebraLoop: SelectLoop = null

    private var bgpPortIdx = 0

    private val activePorts = mutable.Set[UUID]()
    private val portHandlers = mutable.Map[UUID, ActorRef]()

    private case class LocalPortActive(portID: UUID, active: Boolean)

    val localPortsCB = new Callback2[UUID, java.lang.Boolean]() {
        def call(portID: UUID, active: java.lang.Boolean) {
            log.debug("LocalPortActive received from callback")
            self ! LocalPortActive(portID, active)
        }
    }

    override def preStart() {
        log.debug("RoutingManager - preStart - begin")

        super.preStart()
        if (config.getMidolmanBGPEnabled) {
            dataClient.subscribeToLocalActivePorts(localPortsCB)
            bgpPortIdx = config.getMidolmanBGPPortStartIndex
        }

        log.debug("RoutingManager - preStart - end")
    }

    override def receive = {
        case LocalPortActive(portID, true) =>
            log.debug("RoutingManager - LocalPortActive(true)" + portID)
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
            log.debug("RoutingManager - LocalPortActive(false)" + portID)
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
            log.debug("RoutingManager - ExteriorRouterPort: " + port.id)
            // Only exterior virtual router ports support BGP.
            // Create a handler if there isn't one and the port is active
            if (activePorts.contains(port.id))
                log.debug("RoutingManager - port is active: " + port.id)

            if (portHandlers.get(port.id) == None)
                log.debug("RoutingManager - no RoutingHandler actor is " +
                    "registered with port: " + port.id)

            if (activePorts.contains(port.id)
                && portHandlers.get(port.id) == None) {
                bgpPortIdx += 1

                portHandlers.put(
                    port.id,
                    context.actorOf(
                        Props(new RoutingHandler(port, bgpPortIdx, client,
                                dataClient, config, zkConnWatcher, zebraLoop)).
                              withDispatcher("zebra-dispatcher"),
                        name = port.id.toString)
                )
                log.debug("RoutingManager - ExteriorRouterPort - " +
                    "RoutingHandler actor creation requested")
            }
            log.debug("RoutingManager - ExteriorRouterPort - end")

        case port: Port =>
            log.warning("Port type not supported to handle routing protocols.")

        case _ => log.error("Unknown message.")
    }

}
