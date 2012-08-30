/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import java.util.UUID
import akka.actor.Actor
import akka.event.Logging
import com.midokura.midolman.simulation.Chain
import com.midokura.midonet.cluster.client.{ForwardingElementBuilder, Builder, DeviceBuilder}

abstract class DeviceManager(val id: UUID) extends Actor {
    val log = Logging(context.system, this)

    var inFilter: Chain = null;
    var outFilter: Chain = null;

    def configUpdated(): Unit = {
        // TODO(pino): deal with null newCfg

        // Unsubscribe from old inFilter if changed.
        if (null != inFilter && !inFilter.id.equals(getInFilterID)) {
            context.actorFor("..").tell(ChainUnsubscribe(inFilter.id))
            inFilter = null
        }
        // Unsubscribe from old outFilter if changed.
        if (null != outFilter && !outFilter.id.equals(getOutFilterID)) {
            context.actorFor("..").tell(ChainUnsubscribe(outFilter.id))
            outFilter = null
        }

        var waitingForChains = false
        // Do we need to subscribe to new filters?
        if (null != getInFilterID && inFilter == null) {
            context.actorFor("..").tell(ChainRequest(getInFilterID, true))
            waitingForChains = true
        }
        if (null != getOutFilterID && outFilter == null) {
            context.actorFor("..").tell(ChainRequest(getOutFilterID, true))
            waitingForChains = true
        }

        if (!waitingForChains) chainsUpdated()

    }

    protected def updateChain(chain: Chain): Unit = {
        if (chain.id.equals(getInFilterID)) {
            inFilter = chain
            // Send a Port update if we're not waiting for the outFilter
            if (getOutFilterID != null && outFilter != null)
                chainsUpdated()
        } else if (chain.id.equals(getOutFilterID)) {
            outFilter = chain
            // Send a Port update if we're not waiting for the inFilter
            if (getInFilterID != null && inFilter != null)
                chainsUpdated()
        }
        // Else it's a Chain we no longer care about.
    }

    def chainsReady(): Boolean = {
        // Each chain must correspond to its respective filter IDs,
        // or be null if the filters ID is null.
        (getOutFilterID == null || outFilter != null) &&
            (getInFilterID == null || inFilter != null)
    }

    def chainsUpdated()

    def getInFilterID: UUID

    def getOutFilterID: UUID

    override def receive = {
        case chain: Chain => updateChain(chain)
    }

    //class abstract ConcreteBuilder extends
    trait DeviceBuilderImpl[Builder] extends DeviceBuilder[Builder] {
        def build() {
            configUpdated()
        }
    }


}


