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

import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.cluster.Client
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig
import org.midonet.midolman.topology.devices.{PoolHealthMonitor, PoolHealthMonitorMap}

object PoolHealthMonitorMapManager {
    case class TriggerUpdate(mappings: Map[UUID, PoolHealthMonitorConfig])
}

class PoolHealthMonitorMapManager(val client: Client)
        extends Actor with ActorLogWithoutPath {

    import PoolHealthMonitorMapManager._
    import context.system

    @Deprecated
    def fromConfigMap(m: Map[UUID, PoolHealthMonitorConfig]):
    Map[UUID, PoolHealthMonitor] =
        m.map(e => (e._1, PoolHealthMonitor.fromConfig(e._1, e._2)))

    override def preStart() {
        client.getPoolHealthMonitorMap(
            new PoolHealthMonitorMapBuilderImpl(self))
    }

    def receive = {
        case TriggerUpdate(mappings) =>
            VirtualTopologyActor.getRef() !
                PoolHealthMonitorMap(fromConfigMap(mappings))
    }
}
