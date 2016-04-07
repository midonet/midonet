/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.topology

import java.util.{Set => JSet, UUID}

import scala.collection.JavaConverters._

import akka.actor.{Actor, ActorRef}

import org.midonet.cluster.Client
import org.midonet.cluster.client.PortGroupBuilder
import org.midonet.cluster.data.PortGroup
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation
import org.midonet.midolman.topology.PortGroupManager.{TriggerDelete, TriggerUpdate}
import org.midonet.midolman.topology.VirtualTopologyActor.{DeleteDevice, InvalidateFlowsByTag}

object PortGroupManager {
    case class TriggerUpdate(members: Set[UUID])
    case object TriggerDelete
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
        case TriggerUpdate(m) =>
            log.debug("Update triggered for members of port group ID {}", id)
            this.members = m
            publishUpdateIfReady()
        case TriggerDelete =>
            VirtualTopologyActor ! DeleteDevice(id)
        case config: PortGroup =>
            log.debug("Update triggered for config of port group ID {}", id)
            this.config = config
            publishUpdateIfReady()
    }
}

class PortGroupBuilderImpl(val pgMgr: ActorRef)
    extends PortGroupBuilder {

    override def setConfig(pg: PortGroup) {
        pgMgr ! pg
    }

    override def setMembers(members: JSet[UUID]) {
        pgMgr ! TriggerUpdate(members.asScala.toSet)
    }

    override def build(): Unit = { }

    override def deleted(): Unit = {
        pgMgr ! TriggerDelete
    }
}
