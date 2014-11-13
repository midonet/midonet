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
import com.google.inject.name.Named

import org.midonet.cluster.DataClient
import org.midonet.midolman.guice.ClusterModule.StorageReactorTag
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.Bridge.UntaggedVlanId
import org.midonet.midolman.state._
import org.midonet.util.eventloop.Reactor

class ZookeeperStateStorage @Inject() (dataClient: DataClient,
                                       @Named(StorageReactorTag)
                                       val reactor: Reactor,
                                       zkManager: ZkManager,
                                       pathBuilder: PathBuilder,
                                       val connectionWatcher:
                                       ZkConnectionAwareWatcher)
    extends StateStorage with MidolmanLogging {

    override def logSource = "org.midonet.cluster.state"

    @throws[StateAccessException]
    override def bridgeMacTable(@Nonnull bridgeId: UUID, vlanId: Short,
                                ephemeral: Boolean): MacPortMap = {
        ensureBridge(bridgeId)
        ensureBridgeVlan(bridgeId, vlanId)
        dataClient.bridgeGetMacTable(bridgeId, vlanId, ephemeral)
    }

    @throws[StateAccessException]
    override def bridgeIp4MacMap(@Nonnull bridgeId: UUID)
    : Ip4ToMacReplicatedMap = {
        ensureBridge(bridgeId)
        dataClient.getIp4MacMap(bridgeId)
    }

    /** Ensures that the path for the specified bridge is created in the
      * legacy storage. */
    private def ensureBridge(bridgeId: UUID) = {
        // Create path.
        val bridgePath = pathBuilder.getBridgePath(bridgeId)
        val bridgeMacPortsPath =
            pathBuilder.getBridgeMacPortsPath(bridgeId, UntaggedVlanId)
        val bridgeVlansPath = pathBuilder.getBridgeVlansPath(bridgeId)

        // Create the bridge path if it does not exist.
        if (!zkManager.exists(bridgePath)) {
            log.warn("Bridge {} does not exist in state storage, creating.",
                     bridgeId)
            createPath(bridgePath)
            createPath(bridgeMacPortsPath)
            createPath(bridgeVlansPath)
        }
    }

    private def ensureBridgeVlan(bridgeId: UUID, vlanId: Short): Unit = {
        // Create the VLAN if different from the default VLAN.
        if (vlanId != UntaggedVlanId) {

            log.warn("Bridge {} does not have state for VLAN {}, creating.",
                     bridgeId, Short.box(vlanId))

            val bridgeVlanPath = pathBuilder.getBridgeVlanPath(bridgeId, vlanId)
            val bridgeVlanMacPortsPath =
                pathBuilder.getBridgeMacPortsPath(bridgeId, vlanId)
            if (!zkManager.exists(bridgeVlanPath)) {
                createPath(bridgeVlanPath)
            }
            if (!zkManager.exists(bridgeVlanMacPortsPath)) {
                createPath(bridgeVlanMacPortsPath)
            }
        }
    }

    /** Creates a path with no data in the legacy storage */
    private def createPath(path: String): Unit = {
        try {
            log.debug("State storage create path {}", path)
            zkManager.addPersistent(path, new Array[Byte](0))
        } catch {
            case e: StateAccessException =>
                log.error("Failed to create path {} in legacy storage", path, e)
        }
    }
}
