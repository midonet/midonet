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

import org.midonet.midolman.state.MacLearningTable.MacTableUpdate
import org.midonet.packets.MAC
import org.midonet.util.functors.Callback3

object MacLearningTable {
    /** Represents a MAC table update */
    case class MacTableUpdate(vlanId: Short, mac: MAC, oldPort: UUID,
                              newPort: UUID) {
        override val toString = s"{vlan=$vlanId mac=$mac oldPort=$oldPort " +
                                s"newPort=$newPort}"
    }
}

/**
 * A Mac table is a mapping of MAC addresses to port UUIDs. This trait is used
 * as a transition between replicated maps and merged maps, both of which
 * are used to implement MAC tables.
 */
trait MacLearningTable {
    def add(key: MAC, portId: UUID): Unit
    def get(key: MAC): UUID

    /**
     * Removes the mapping if the given MAC is associated to the given port.
     */
    def remove(key: MAC, portId: UUID): Unit

    def remove(key: MAC): Unit

    /**
     * Returns an observable that emits updates of the form
     * (mac, oldPort, newPort) whenever a mapping in the mac table changes.
     */
    def observable: Observable[MacTableUpdate]

    def notify(cb: Callback3[MAC, UUID, UUID]): Unit

    /**
     * Stops the MAC table.
     */
    def complete(): Unit
}
