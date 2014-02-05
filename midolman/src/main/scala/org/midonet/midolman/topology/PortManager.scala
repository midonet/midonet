/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import java.util.UUID

import akka.actor.Actor

import org.midonet.cluster.Client
import org.midonet.cluster.client.Port
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.topology.builders.PortBuilderImpl
import org.midonet.midolman.topology.PortManager.TriggerUpdate

object PortManager{
    case class TriggerUpdate(port: Port)
}

class PortManager(id: UUID, val clusterClient: Client)
        extends DeviceWithChains {
    import context.system

    protected var cfg: Port = _
    private var changed = false

    def topologyReady(topology: Topology) {
        log.debug("Sending a Port to the VTA")
        // TODO(ross) better cloning this port before passing it
        VirtualTopologyActor ! cfg

        if (changed) {
            VirtualTopologyActor !
                InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(cfg.id))
            changed = false
        }
    }

    override def preStart() {
        log.info("preStart, port id {}", id)
        clusterClient.getPort(id, new PortBuilderImpl(self))
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(p: Port) =>
            changed = cfg != null
            cfg = p
            prefetchTopology()
    }
}
