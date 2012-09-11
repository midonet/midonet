/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import bgp.BGPPortHandler
import com.midokura.midonet.cluster.DataClient
import com.google.inject.Inject
import java.util.UUID
import com.midokura.util.functors.Callback2
import com.midokura.midolman.config.MidolmanConfig
import topology.VirtualTopologyActor
import topology.VirtualTopologyActor.PortRequest
import com.midokura.midonet.cluster.client.{Port, ExteriorRouterPort}
import collection.mutable

object BGPManager extends Referenceable {
    val Name = "BGPManager"
}

class BGPManager extends Actor with ActorLogging {

    @Inject
    var dataClient: DataClient = null
    @Inject
    var config: MidolmanConfig = null

    private var bgpPortIdx = 0

    private val activePorts = mutable.Set[UUID]()
    private val portHandlers = mutable.Map[UUID, ActorRef]()

    private case class LocalPortActive(portID: UUID, active: Boolean)
    val localPortsCB = new Callback2[UUID, java.lang.Boolean]() {
        def call(portID: UUID, active: java.lang.Boolean) {
            self ! LocalPortActive(portID, active)
        }
    }

    override def preStart() {
        super.preStart()
        if (config.getMidolmanBGPEnabled) {
            dataClient.subscribeToLocalActivePorts(localPortsCB)
            bgpPortIdx = config.getMidolmanBGPPortStartIndex
        }
    }

    override def receive = {
        case LocalPortActive(portID, true) =>
            activePorts.add(portID)
            // Request the port configuration
            VirtualTopologyActor.getRef() ! PortRequest(portID, false)

        case LocalPortActive(portID, false) =>
            activePorts.remove(portID)
            // TODO(pino): tear down the BGPPortHandler for this port.

        case port: ExteriorRouterPort =>
            // Only exterior virtual router ports support BGP.
            // Create a handler if there isn't one and the port is active
            if (activePorts(port.id) && portHandlers(port.id) == null) {
                bgpPortIdx += 1
                portHandlers.put(
                    port.id,
                    context.actorOf(Props(new BGPPortHandler(port, bgpPortIdx)),
                                    name = port.id.toString()))
            }

        case port: Port[_] =>
            // Ignore all other port types.
    }

}
