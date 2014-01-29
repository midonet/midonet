/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import builders.PortBuilderImpl
import java.util.UUID
import org.midonet.cluster.Client
import org.midonet.midolman.topology.PortManager.TriggerUpdate
import org.midonet.cluster.client.Port
import org.midonet.midolman.FlowController.InvalidateFlowsByTag

object PortManager{
    case class TriggerUpdate(port: Port)
}

class PortManager(id: UUID, val clusterClient: Client)
    extends DeviceManager(id) {
    import context.system

    private var port: Port = null
    private var changed = false

    override def chainsUpdated() {
        log.info("chains updated, new port {}, {}invalidating flows",
                 port, if (changed) "" else "not ");
        // TODO(ross) better cloning this port before passing it
        VirtualTopologyActor ! port

        if (changed) {
            VirtualTopologyActor !
                InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(port.id))
            changed = false
        }
    }

    override def preStart() {
        log.info("preStart, port id {}", id)
        clusterClient.getPort(id, new PortBuilderImpl(self))
    }

    override def isAdminStateUp = {
        port match {
            case null => false
            case _ => port.adminStateUp
        }
    }

    override def getInFilterID = {
        port match {
            case null => null
            case _ => port.inFilterID
        }
    }

    override def getOutFilterID = {
        port match {
            case null => null
            case _ => port.outFilterID
        }
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(p: Port) =>
            changed = port != null
            port = p
            configUpdated()
    }
}
