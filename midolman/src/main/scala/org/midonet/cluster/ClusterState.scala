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

import rx.Observable

import org.midonet.cluster.ClusterState.LocalPortActive

object ClusterState {

    /**
     * Indicates when a virtual network port becomes active.
     * @param portId The identifier of the port that is to be changed to active
     *               or inactive.
     * @param active True if the port is ready to emit/receive, false otherwise.
     */
    case class LocalPortActive(portId: UUID, active: Boolean)
}

/**
 * The cluster state API.
 */
trait ClusterState {

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
     */
    def observableLocalPortActive: Observable[LocalPortActive]
}
