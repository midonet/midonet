/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import java.util.UUID
import akka.actor.{ActorSystem, Actor}
import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.topology.VirtualTopologyActor.{ChainRequest,
                                                            ChainUnsubscribe}
import org.midonet.midolman.logging.ActorLogWithoutPath

abstract class DeviceManager(val id: UUID) extends Actor with ActorLogWithoutPath {
    var inFilter: Chain = null
    var outFilter: Chain = null

    def configUpdated(): Unit = {
        // Unsubscribe from old inFilter if changed.
        if (null != inFilter && !inFilter.id.equals(getInFilterID)) {
            VirtualTopologyActor.getRef() ! ChainUnsubscribe(inFilter.id)
            inFilter = null
        }
        // Unsubscribe from old outFilter if changed.
        if (null != outFilter && !outFilter.id.equals(getOutFilterID)) {
            VirtualTopologyActor.getRef() ! ChainUnsubscribe(outFilter.id)
            outFilter = null
        }

        var waitingForChains = false
        // Do we need to subscribe to new filters?
        if (waitingForInFilter) {
            log.debug("subscribing to ingress chain {}", getInFilterID)
            VirtualTopologyActor.getRef() !
                    ChainRequest(getInFilterID, update = true)
            waitingForChains = true
        }
        if (waitingForOutFilter) {
            log.debug("subscribing to egress chain {}", getOutFilterID)
            VirtualTopologyActor.getRef() !
                ChainRequest(getOutFilterID, update = true)
            waitingForChains = true
        }

        if (!waitingForChains) chainsUpdated()

    }

    protected def updateChain(chain: Chain): Unit = {
        if (chain.id.equals(getInFilterID)) {
            log.debug("Received ingress filter {}", chain.id)
            inFilter = chain
            // Send a Port update if we're not waiting for the outFilter
            if (!waitingForOutFilter)
                chainsUpdated()
        } else if (chain.id.equals(getOutFilterID)) {
            log.debug("Received egress filter {}", chain.id)
            outFilter = chain
            // Send a Port update if we're not waiting for the inFilter
            if (!waitingForInFilter)
                chainsUpdated()
        } else {
            // Else it's a Chain we no longer care about.
            log.debug("Received an unused filter {}", chain.id)
        }
    }

    private def waitingForInFilter =
        null != getInFilterID && inFilter == null
    private def waitingForOutFilter =
        null != getOutFilterID && outFilter == null

    def chainsReady = !waitingForInFilter && !waitingForOutFilter

    def chainsUpdated()

    def isAdminStateUp: Boolean

    def getInFilterID: UUID

    def getOutFilterID: UUID

    override def receive = {
        case chain: Chain => updateChain(chain)
    }
}


