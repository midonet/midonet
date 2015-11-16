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
import org.midonet.cluster.backend.Directory
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
    override def bridgeMacTable(@Nonnull bridgeId: UUID, vlanId: Short,
                                ephemeral: Boolean): MacPortMap = {
        ensureBridgePaths(bridgeId)
        ensureBridgeVlanPaths(bridgeId, vlanId)
        val map = new MacPortMap(
            zkManager.getSubDirectory(pathBuilder.getBridgeMacPortsPath(bridgeId, vlanId)),
            ephemeral)
        map.setConnectionWatcher(connectionWatcher)
        map
    }

    private def getIP4MacMapDirectory(id: UUID): Directory = {
        val path = pathBuilder.getBridgeIP4MacMapPath(id)
        if (!zkManager.exists(path))
            zkManager.addPersistent(path, null)
        zkManager.getSubDirectory(path)
    }

    @throws[StateAccessException]
    override def bridgeIp4MacMap(@Nonnull bridgeId: UUID): Ip4ToMacReplicatedMap = {
        ensureBridgePaths(bridgeId)
        val map = new Ip4ToMacReplicatedMap(getIP4MacMapDirectory(bridgeId))
        map.setConnectionWatcher(connectionWatcher)
        map
    }

    @throws[StateAccessException]
    override def routerArpTable(@Nonnull routerId: UUID): ArpTable = {
        ensureRouterPaths(routerId)
        val arpTable = new ArpTable(zkManager.getSubDirectory(
                pathBuilder.getRouterArpTablePath(routerId)))
        arpTable.setConnectionWatcher(connectionWatcher)
        arpTable
    }

    /** Ensures that the path for the specified bridge is created in the
      * legacy storage. */
    @throws[StateAccessException]
    private def ensureBridgePaths(bridgeId: UUID) = {
        // Create path.
        val bridgesPath = pathBuilder.getBridgesPath
        val bridgePath = pathBuilder.getBridgePath(bridgeId)
        val bridgeMacPortsPath =
            pathBuilder.getBridgeMacPortsPath(bridgeId, UntaggedVlanId)
        val bridgeVlansPath = pathBuilder.getBridgeVlansPath(bridgeId)

        // Create the bridge path if it does not exist.
        log.info("Creating bridge {} path in state storage.", bridgeId)
        createPath(bridgesPath)
        createPath(bridgePath)
        createPath(bridgeMacPortsPath)
        createPath(bridgeVlansPath)
    }

    /** Ensures that the path for the specified bridge and VLAN is created in
      * the legacy storage. */
    @throws[StateAccessException]
    private def ensureBridgeVlanPaths(bridgeId: UUID, vlanId: Short): Unit = {
        // Create the VLAN if different from the default VLAN.
        if (vlanId != UntaggedVlanId) {

            log.info("Creating bridge {} VLAN {} path in state storage.",
                     bridgeId, Short.box(vlanId))

            val bridgeVlanPath = pathBuilder.getBridgeVlanPath(bridgeId, vlanId)
            val bridgeVlanMacPortsPath =
                pathBuilder.getBridgeMacPortsPath(bridgeId, vlanId)
            createPath(bridgeVlanPath)
            createPath(bridgeVlanMacPortsPath)
        }
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
