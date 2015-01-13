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
package org.midonet.cluster.state

import java.util.UUID

import javax.annotation.Nonnull

import com.google.inject.Inject
import com.google.inject.name.Named

import org.midonet.cluster.DataClient
import org.midonet.midolman.guice.ClusterModule.StorageReactorTag
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap, StateAccessException, ZkConnectionAwareWatcher}
import org.midonet.util.eventloop.Reactor

class ZookeeperStateStorage @Inject() (dataClient: DataClient,
                                       @Named(StorageReactorTag)
                                       val reactor: Reactor,
                                       val connectionWatcher:
                                           ZkConnectionAwareWatcher)
        extends StateStorage {

    @throws[StateAccessException]
    override def getBridgeMacTable(@Nonnull bridgeId: UUID,
                          vlanId: Short,
                          ephemeral: Boolean): MacPortMap = {
        dataClient.bridgeGetMacTable(bridgeId, vlanId, ephemeral)
    }

    @throws[StateAccessException]
    def getBridgeIp4MacMap(@Nonnull bridgeId: UUID): Ip4ToMacReplicatedMap = {
        dataClient.getIp4MacMap(bridgeId)
    }
}
