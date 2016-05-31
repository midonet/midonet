/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.state

import java.util.UUID

import javax.annotation.Nonnull

import com.google.inject.Inject
import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, StateAccessException}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.serialization.Serializer
import org.midonet.midolman.simulation.Bridge.UntaggedVlanId
import org.midonet.midolman.state._

/**
 * An implementation of the [[LegacyStorage]] trait using the legacy ZooKeeper
 * managers as backend.
 */
class ZookeeperLegacyStorage @Inject()(connectionWatcher: ZkConnectionAwareWatcher,
                                       serializer: Serializer,
                                       zkManager: ZkManager,
                                       pathBuilder: PathBuilder)
        extends LegacyStorage with MidolmanLogging {

    override def logSource = "org.midonet.cluster.state"

    @throws[StateAccessException]
    override def routerArpTable(@Nonnull routerId: UUID): ArpTable = {
        ensureRouterPaths(routerId)
        val arpTable = new ArpTable(zkManager.getSubDirectory(
                pathBuilder.getRouterArpTablePath(routerId)))
        arpTable.setConnectionWatcher(connectionWatcher)
        arpTable
    }

    /** Ensures that the path for the specified router is created in the legacy
      * storage. */
    @throws[StateAccessException]
    private def ensureRouterPaths(routerId: UUID): Unit = {
        // Create path.
        val routersPath = pathBuilder.getRoutersPath
        val routerPath = pathBuilder.getRouterPath(routerId)
        val routerArpTablePath = pathBuilder.getRouterArpTablePath(routerId)
        val routerRoutingTablePath = pathBuilder.getRouterRoutingTablePath(routerId)

        // Create the router path if it does not exist.
        log.info("Creating router {} path in state storage.", routerId)
        createPath(routersPath)
        createPath(routerPath)
        createPath(routerArpTablePath)
        createPath(routerRoutingTablePath)
    }

    /** Creates a path with no data in the legacy storage */
    private def createPath(path: String): Unit = {
        try {
            log.debug("State storage create path {}", path)
            zkManager.addPersistent(path, new Array[Byte](0))
        } catch {
            case e: StatePathExistsException =>
                log.debug("Path {} already exists in legacy storage", path)
            case e: StateAccessException =>
                log.error("Failed to create path {} in legacy storage", path, e)
        }
    }
}
