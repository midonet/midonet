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

package org.midonet.cluster.storage

import java.util.UUID

import com.google.inject.Inject

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.{StateTable, StateTableStorage}
import org.midonet.packets.{IPv4Addr, MAC}

final class LegacyStateTableStorage @Inject()(dataClient: DataClient)
    extends StateTableStorage {

    /** Returns the IPv4 ARP table for the specified bridge. */
    def bridgeArpTable(bridgeId: UUID): StateTable[IPv4Addr, MAC] = {
        new LegacyArpTable(bridgeId, dataClient)
    }

}
