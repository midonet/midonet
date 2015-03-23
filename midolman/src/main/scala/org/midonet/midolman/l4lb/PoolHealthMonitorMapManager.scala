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
package org.midonet.midolman.l4lb

import java.util.UUID

import scala.collection.immutable.Map

import akka.actor.Actor

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.cluster.Client
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.PoolHealthMonitorMap
import org.midonet.midolman.topology.{PoolHealthMonitorMapper, VirtualTopology, VirtualTopologyActor}
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig
import org.midonet.util.functors.{makeFunc1, makeAction1}

object PoolHealthMonitorMapManager {
    case class TriggerUpdate(mappings: Map[UUID, PoolHealthMonitorConfig])
    case class PoolHealthMonitorLegacyMap(mappings: Map[UUID, PoolHealthMonitorConfig])
        extends Device
}

class PoolHealthMonitorMapManager(val client: Client, config: MidolmanConfig)
        extends Actor with ActorLogWithoutPath {

    import PoolHealthMonitorMapManager._
    import context.system

    override def preStart() {
        if (config.zookeeper.useNewStack) {
            VirtualTopology.observable[PoolHealthMonitorMap](
                PoolHealthMonitorMapper.PoolHealthMonitorMapKey)
                .map[PoolHealthMonitorLegacyMap](makeFunc1 {_.toConfig})
                .subscribe(makeAction1[PoolHealthMonitorLegacyMap]
                    (VirtualTopologyActor.getRef() ! _))
        } else {
            client.getPoolHealthMonitorMap(
                new PoolHealthMonitorMapBuilderImpl(self))
        }
    }

    def receive = {
        case TriggerUpdate(mappings) => {
            VirtualTopologyActor.getRef() ! PoolHealthMonitorLegacyMap(mappings)
        }
    }
}
