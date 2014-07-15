/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import akka.actor.{ActorRef, Actor}
import collection.JavaConverters._
import java.util.{Set => JSet, UUID}
import scala.collection.breakOut

import org.midonet.cluster.Client
import org.midonet.cluster.client.PortGroupBuilder
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation
import org.midonet.sdn.flows.FlowTagger
import org.midonet.cluster.data.PortGroup

object PortGroupManager {
    case class TriggerUpdate(members: Set[UUID])
}

class PortGroupManager(val id: UUID, val clusterClient: Client) extends Actor
    with ActorLogWithoutPath {
    import PortGroupManager._
    import context.system

    private var config: PortGroup = null
    private var members: Set[UUID] = null

    override def preStart() {
        clusterClient.getPortGroup(id, new PortGroupBuilderImpl(self))
    }

    private def publishUpdateIfReady() {
        if (members == null) {
            log.debug(s"Not publishing port group $id. Still waiting for members.")
            return
        }
        if (config == null) {
            log.debug(s"Not publishing port group $id. Still waiting for config.")
            return
        }
        log.debug(s"Publishing update for port group $id.")

        val simGroup = new simulation.PortGroup(config.getId, config.getName,
                                                config.isStateful, members)
        VirtualTopologyActor ! simGroup
        VirtualTopologyActor ! InvalidateFlowsByTag(simGroup.deviceTag)
    }

    override def receive = {
        case TriggerUpdate(members) => {
            log.debug("Update triggered for members of port group ID {}", id)
            this.members = members
            publishUpdateIfReady()
        }
        case config: PortGroup => {
            log.debug("Update triggered for config of port group ID {}", id)
            this.config = config
            publishUpdateIfReady()
        }
    }
}

class PortGroupBuilderImpl(val pgMgr: ActorRef)
    extends PortGroupBuilder {
    import PortGroupManager.TriggerUpdate


    def setConfig(pg: PortGroup) {
        pgMgr ! pg
    }

    def setMembers(members: JSet[UUID]) {
        pgMgr ! TriggerUpdate(members.asScala.toSet)
    }
}
