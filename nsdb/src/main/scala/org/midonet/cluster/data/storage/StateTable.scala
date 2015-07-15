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

package org.midonet.cluster.data.storage

import java.util.UUID

import rx.Observable

import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.packets.MAC

object StateTable {
    case class MacTableUpdate(vlanId: Short, mac: MAC, oldPort: UUID,
                              newPort: UUID)
    extends Update[MAC, UUID](mac, oldPort, newPort) {
        override val toString = s"{vlan=$vlanId mac=$mac oldPort=$oldPort " +
                                s"newPort=$newPort}"
    }
}

/**
 * The base trait for a state table, containing mappings between keys and
 * values. Mapping entries can be `learned` or `persistent`. For learned entries
 * the underlying implementation must provide a mechanism to discriminate
 * between multiple concurrent writes (or opinions). Persistent entries take
 * precedence over learned entries, and cannot be overwritten by the latter.
 */
trait StateTable[K, V, T <: Update[K, V]] {

    /** Adds an opinion key value pair to the state table. */
    def add(key: K, value: V): Unit

    /** Adds a persistent key value pair to the state table. */
    def addPersistent(key: K, value: V): Unit

    /** Removes the opinion for the specified key from the state table. */
    def remove(key: K): Unit

    /** Removes a key value pair from the state table, either learned or
      * persistent. */
    def remove(key: K, value: V): Unit

    /** Returns whether the table contains a value for the specified key,
      * either learned or persistent. */
    def contains(key: K): Boolean

    /** Returns whether the table contains the key value pair, either learned or
      * persistent. */
    def contains(key: K, value: V): Boolean

    /** Returns whether the table contains the persistent key value pair. */
    def containsPersistent(key: K, value: V): Boolean

    /** Gets the value for the specified key or null if the mapping does not
        exist. */
    def get(key: K): V

    /** Gets the set of keys corresponding the specified value. */
    def getByValue(value: V): Set[K]

    /** Gets a read-only snapshot for the current state table. */
    def snapshot: Map[K, V]

    /** Returns an observable that notifies the updates to the current state
      * table. */
    def observable: Observable[T]

    /** Closes the map and completes its observable. */
    def close(): Unit
}
