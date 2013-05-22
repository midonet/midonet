/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import builders.PortBuilderImpl
import java.util.UUID
import org.midonet.cluster.Client
import org.midonet.midolman.topology.PortManager.TriggerUpdate
import org.midonet.cluster.client.Port
import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag

object PortManager{
    case class TriggerUpdate(port: Port[_])
}

class PortManager(id: UUID, val clusterClient: Client)
    extends DeviceManager(id) {
    var port: Port[_] = null

    override def chainsUpdated() {
        log.info("chains updated, new port {}", port)
        // TODO(ross) better cloning this port before passing it
        context.actorFor("..").tell(port)
    }

    override def preStart() {
        log.info("preStart, port id {}", id)
        clusterClient.getPort(id, new PortBuilderImpl(self))
    }

    override def getInFilterID = {
        port match {
            case null => null;
            case _ => port.inFilterID
        }
    }

    override def getOutFilterID = {
        port match {
            case null => null;
            case _ => port.outFilterID
        }
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(p: Port[_]) =>
            port = p
            configUpdated()
            context.actorFor("..") !
                InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(p.id))

    }
}
