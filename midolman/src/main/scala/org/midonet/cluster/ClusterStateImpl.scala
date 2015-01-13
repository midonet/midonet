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
package org.midonet.cluster

import java.util.UUID

import com.google.inject.Inject
import com.google.inject.name.Named

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.storage.{OwnershipConflictException, NotFoundException, StorageWithOwnership}
import org.midonet.cluster.models.Topology.Port
import org.midonet.midolman.guice.ClusterModule.StorageReactorTag
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.zkManagers.PortZkManager
import org.midonet.midolman.state.{PortConfig, PortDirectory, StateAccessException}
import org.midonet.util.eventloop.Reactor
import org.midonet.util.functors.makeRunnable

class ClusterStateImpl @Inject()(config: ZookeeperConfig,
                                 storage: StorageWithOwnership,
                                 @Named(StorageReactorTag) reactor: Reactor,
                                 // Legacy
                                 portZkManager: PortZkManager,
                                 routerManager: ClusterRouterManager)
    extends ClusterState with MidolmanLogging {

    private val subjectLocalPortActive = PublishSubject.create[LocalPortActive]()

    override def setPortLocalAndActive(portId: UUID, hostId: UUID,
                                       active: Boolean): Unit = runOnReactor {
         // Activate the port for legacy ZK storage.
         if (config.isLegacyStorageEnabled) {
            var portConfig: PortConfig = null
            try {
                portZkManager.setActivePort(portId, hostId, active)
                portConfig = portZkManager.get(portId)
            }
            catch {
                case e: StateAccessException =>
                    log.error("Error retrieving the configuration for port {}",
                              portId, e)
                case e: SerializationException =>
                    log.error("Error serializing the configuration for port {}",
                              portId, e)
            }
            if (portConfig.isInstanceOf[PortDirectory.RouterPortConfig]) {
                val deviceId: UUID = portConfig.device_id
                routerManager.updateRoutesBecauseLocalPortChangedStatus(
                    deviceId, portId, active)
            }
        }
        // Activate the port for cluster storage.
        if (config.isClusterStorageEnabled) {
            try {
                if (active) {
                    storage.updateOwner(classOf[Port], portId, hostId, true)
                } else {
                    storage.deleteOwner(classOf[Port], portId, hostId)
                }
            } catch {
                case e: NotFoundException =>
                    log.error("Port {} does not exist", portId)
                case e: OwnershipConflictException =>
                    log.error("Host {} does not have permission to activate " +
                              "or deactivate port {}", hostId, portId)
            }
        }
        subjectLocalPortActive.onNext(LocalPortActive(portId, active))
    }

    override def observableLocalPortActive: Observable[LocalPortActive] =
        subjectLocalPortActive.asObservable

    private def runOnReactor(fn: => Unit) = reactor.submit(makeRunnable(fn))

}
