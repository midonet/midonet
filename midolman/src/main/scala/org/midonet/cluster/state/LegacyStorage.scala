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

import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap, _}

/**
 * A trait defining the cluster state API. Currently, this trait represents a
 * transitional interface from the legacy [[org.midonet.cluster.DataClient]].
 */
trait LegacyStorage {

    /**
     * Gets the MAC-port table for the specified bridge.
     */
    @throws[StateAccessException]
    def bridgeMacTable(@Nonnull bridgeId: UUID,
                       vlanId: Short, ephemeral: Boolean): MacPortMap


    /**
     * Gets the IP-MAC table for the specified bridge.
     */
    @throws[StateAccessException]
    def bridgeIp4MacMap(@Nonnull bridgeId: UUID): Ip4ToMacReplicatedMap

    /**
     * Gets the ARP table for the specified router.
     */
    @throws[StateAccessException]
    def routerArpTable(@Nonnull routerId: UUID): ArpTable
}
