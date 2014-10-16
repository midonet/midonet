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

import org.midonet.cluster.client.PoolHealthMonitorMapBuilder
import java.util.UUID
import org.slf4j.{LoggerFactory, Logger}
import akka.actor.ActorRef
import scala.collection.immutable.Map
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig


class PoolHealthMonitorMapBuilderImpl(val poolHealthMonitorMapManager: ActorRef)
        extends PoolHealthMonitorMapBuilder {

    private final val log: Logger =
        LoggerFactory.getLogger(classOf[PoolHealthMonitorMapBuilderImpl])

    private var poolIdToMappingConfig
        = Map[UUID, PoolHealthMonitorConfig]()

    def build(): Unit = {
        poolHealthMonitorMapManager !
            PoolHealthMonitorMapManager.TriggerUpdate(poolIdToMappingConfig)
    }

    /**
    * Adds a map of pools and health monitors..
    *
    * @param mappings Map of pool and health monitor mappings
    */
    override def set(mappings: Map[UUID, PoolHealthMonitorConfig]) {
        log.debug("PoolHealthMonitorMapBuilderImpl.set")

        mappings match {
            case null =>
                this.poolIdToMappingConfig
                    = Map[UUID, PoolHealthMonitorConfig]()
            case _ =>
                this.poolIdToMappingConfig = mappings
        }
    }
}
