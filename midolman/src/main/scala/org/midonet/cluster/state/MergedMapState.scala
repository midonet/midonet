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

import org.midonet.cluster.data.storage.MergedMap
import org.midonet.cluster.state.MergedMapStateStorage.PortTS
import org.midonet.midolman.state.StateAccessException
import org.midonet.packets.MAC

/**
 * A trait defining the cluster state API based on Merged Maps. The cluster
 * state API based on replicated maps is named [[LegacyStorage]].
 */
trait MergedMapState {
    /**
     * Returns true iff merged maps are enabled.
     */
    def isEnabled: Boolean

    /**
     * Gets the MAC-port merged map for the specified bridge.
     */
    @throws[StateAccessException]
    def bridgeMacMergedMap(@Nonnull bridgeId: UUID, vlanId: Short)
    : MergedMap[MAC, PortTS]
}
