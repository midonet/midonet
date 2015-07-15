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

package org.midonet.midolman.state

import java.util.UUID

import rx.Observable

import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.data.storage.StateTable.MacTableUpdate
import org.midonet.packets.MAC
import org.midonet.util.functors.Callback3

/**
 * A Mac table is a mapping of MAC addresses to port UUIDs. This trait is used
 * as a transition between replicated maps and merged maps, both of which
 * are used to implement MAC tables.
 */
trait MacLearningTable extends StateTable[MAC, UUID, MacTableUpdate] {
    def add(key: MAC, portId: UUID): Unit
    def get(key: MAC): UUID
    def notify(cb: Callback3[MAC, UUID, UUID]): Unit

    /** Methods below are empty implementations of methods from the StateTable
        trait. In doing so, classes extending this trait do not need to
        implement these methods. */
    def remove(key: MAC): Unit = ???
    def addPersistent(key: MAC, portId: UUID): Unit = ???
    def contains(key: MAC): Boolean = ???
    def contains(key: MAC, portId: UUID): Boolean = ???
    def containsPersistent(key: MAC, portId: UUID): Boolean = ???
    def getByValue(portId: UUID): Set[MAC] = ???
    def snapshot: Map[MAC, UUID] = ???
    def observable: Observable[MacTableUpdate] = ???
    def close(): Unit = ???
}
