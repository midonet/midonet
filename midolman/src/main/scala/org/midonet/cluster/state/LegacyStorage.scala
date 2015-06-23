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

import rx.Observable

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap, StateAccessException, _}

/**
 * Indicates when a virtual network port becomes active.
 * @param portId The identifier of the port that is to be changed to active
 *               or inactive.
 * @param active True if the port is ready to emit/receive, false otherwise.
 */
case class LocalPortActive(portId: UUID, active: Boolean)

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
     * Gets the replicated route set for the router's routing table.
     */
    @throws[StateAccessException]
    def routerRoutingTable(@Nonnull routerId: UUID): ReplicatedSet[Route]

    /**
     * Gets the ARP table for the specified router.
     */
    @throws[StateAccessException]
    def routerArpTable(@Nonnull routerId: UUID): ArpTable

    /**
     * Inform the storage cluster that the port is active. This may be used by
     * the cluster to do trigger related processing e.g. updating the router's
     * forwarding table if this port belongs to a router.
     *
     * @param portId The identifier of the port
     * @param host The identifier of the host where it's active
     * @param active True / false depending on what state we want in the end
     *               for the port
     */
    def setPortLocalAndActive(portId: UUID, host: UUID, active: Boolean): Unit

    /**
     * An observable that emits notifications when a local port becomes active,
     * or inactive. This can be used, e.g. by the BGP manager, to discover local
     * ports, such that it may then observe those specific ports and manage
     * their BGP entries, if any.
     *
     * The notifications are emitted on the thread specified by the storage
     * reactor.
     */
    def localPortActiveObservable: Observable[LocalPortActive]

}
