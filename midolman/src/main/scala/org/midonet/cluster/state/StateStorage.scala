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

import org.midonet.cluster.backend.zookeeper.{StateAccessException, ZkConnectionAwareWatcher}
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap}
import org.midonet.util.eventloop.Reactor

/**
 * A trait defining the cluster state API. Currently, this trait represents a
 * transitional interface from the legacy [[org.midonet.cluster.DataClient]].
 */
trait StateStorage {

    val reactor: Reactor

    val connectionWatcher: ZkConnectionAwareWatcher

    @throws[StateAccessException]
    def getBridgeMacTable(@Nonnull bridgeId: UUID,
                          vlanId: Short,
                          ephemeral: Boolean): MacPortMap


    @throws[StateAccessException]
    def getBridgeIp4MacMap(@Nonnull bridgeId: UUID): Ip4ToMacReplicatedMap
}
