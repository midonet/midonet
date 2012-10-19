/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import builders.PortBuilderImpl
import java.util.UUID
import com.midokura.packets.IntIPv4
import com.midokura.midonet.cluster.Client
import com.midokura.midolman.topology.PortManager.TriggerUpdate
import com.midokura.midonet.cluster.client.Port
import com.midokura.midolman.FlowController
import com.midokura.midolman.FlowController.InvalidateFlowsByTag

object PortManager{
    case class TriggerUpdate(port: Port[_])
}

class PortManager(id: UUID, val clusterClient: Client)
    extends DeviceManager(id) {
    var port: Port[_] = null

    override def chainsUpdated() {
        log.info("chains updated")
        context.actorFor("..").tell(port)
    }

    override def preStart() {
        log.info("preStart")
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
            FlowController.getRef() !
                InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(p.id))
            configUpdated()

    }
}
