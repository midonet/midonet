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

package org.midonet.cluster.client

import java.util.UUID

import rx.Observable

import org.midonet.cluster.data.storage.state_table.MacTableMergedMap.MacTableUpdate
import org.midonet.packets.MAC
import org.midonet.util.functors.Callback3

trait MacLearningTable {
    def add(key: MAC, portId: UUID): Unit
    def get(key: MAC): UUID
    def remove(mac: MAC, portID: UUID): Unit
    def notify(cb: Callback3[MAC, UUID, UUID]): Unit
    def observable(): Observable[MacTableUpdate]
    def complete(): Unit
}
