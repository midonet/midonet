/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.routingprotocols

import collection.mutable
import akka.actor._
import com.google.inject.Inject
import java.util.UUID

import com.midokura.util.functors.Callback2
import com.midokura.midolman.config.MidolmanConfig
import com.midokura.midonet.cluster.{Client, DataClient}
import com.midokura.midonet.cluster.client.{Port, ExteriorRouterPort}
import com.midokura.midolman.Referenceable
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import com.midokura.midolman.topology.VirtualTopologyActor

object RoutingManagerActor extends Referenceable {
    override val Name = "RoutingManager"
}

class RoutingManagerActor extends Actor with ActorLogging {

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    var dataClient: DataClient = null
    @Inject
    var config: MidolmanConfig = null
    @Inject
    val client: Client = null

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
                VirtualTopologyActor.getRef() ! PortRequest(portID, update = false)
            }

        case LocalPortActive(portID, false) =>
            log.debug("RoutingManager - LocalPortActive(false)" + portID)
            if (!activePorts.contains(portID)) {
                log.error("we should have had information about port {}", portID)
            } else {
                activePorts.remove(portID)

                // Only exterior ports can have a routing handler
                val result = portHandlers.get(portID)
                result match {
                    case None =>
                    case Some(routingHandler) => context.stop(routingHandler)
                }
            }

        case port: ExteriorRouterPort =>
            log.debug("RoutingManager - ExteriorRouterPort: " + port.id)
            // Only exterior virtual router ports support BGP.
            // Create a handler if there isn't one and the port is active
            if (activePorts.contains(port.id))
                log.debug("RoutingManager - port is active: " + port.id)

            if (portHandlers.get(port.id) == None)
                log.debug("RoutingManager - no RoutingHandler actor is registered with port: " + port.id)

            if (activePorts.contains(port.id) && portHandlers.get(port.id) == None) {
                bgpPortIdx += 1

                portHandlers.put(
                    port.id,
                    context.actorOf(
                        Props(new RoutingHandler(port, bgpPortIdx, client, dataClient)).withDispatcher("actors.stash-dispatcher"),
                        name = port.id.toString)
                )
                log.debug("RoutingManager - ExteriorRouterPort - RoutingHandler actor creation requested")
            }
            log.debug("RoutingManager - ExteriorRouterPort - end")

        case port: Port[_] =>
            log.debug("Port type not supported to handle routing protocols.")

        case _ => log.error("Unknown message.")
    }

}
