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

import rx.Observable

import org.midonet.cluster.data.storage.StateTable.Update

object StateTable {

    case class Update[K, V](key: K, oldValue: V, newValue: V)

}

/**
 * The base trait for a state table, containing mappings between keys and
 * values. Mapping entries can be `learned` or `persistent`. For learned entries
 * the underlying implementation must provide a mechanism to discriminate
 * between multiple concurrent writes (or opinions). Persistent entries take
 * precedence over learned entries, and cannot be overwritten by the latter.
 */
trait StateTable[K, V] {

    /** Starts the synchronization of the state table. */
    def start(): Unit

    /** Stops the synchronization of the state table. */
    def stop(): Unit

    /** Adds an opinion key value pair to the state table. */
    def add(key: K, value: V): Unit

    /** Adds a persistent key value pair to the state table. */
    def addPersistent(key: K, value: V): Unit

    /** Removes the opinion for the specified key from the state table. */
    def remove(key: K): V

    /** Removes a key value pair from the state table. */
    def remove(key: K, value: V): V

    /** Removes a persistent key value pair from the state table. */
    def removePersistent(key: K, value: V): V

    /** Returns whether the cached version of the table contains a value for the
      * specified key, either learned or persistent. */
    def containsLocal(key: K): Boolean

    /** Returns whether the cached version of the table contains the key value
      * pair, either learned or persistent. */
    def containsLocal(key: K, value: V): Boolean

    /** Returns whether the remote table contains a value for the specified key,
      * either learned or persistent. */
    def containsRemote(key: K): Boolean

    /** Returns whether the remote table contains a value for the specified key,
      * either learned or persistent. */
    def containsRemote(key: K, value: V): Boolean

    /** Returns whether the remote table contains the persistent key value pair. */
    def containsPersistent(key: K, value: V): Boolean

    /** Gets the local cached value for the specified key. */
    def getLocal(key: K): V

    /** Gets the remote value for the specified key. */
    def getRemote(key: K): V

    /** Gets the local cached set of keys corresponding to the specified value. */
    def getLocalByValue(value: V): Set[K]

    /** Gets the remote set of keys corresponding to the specified value. */
    def getRemoteByValue(value: V): Set[K]

    /** Gets a read-only snapshot for the current state table. */
    def localSnapshot: Map[K, V]

    /** Gets a read-only snapshot for the current state table. */
    def remoteSnapshot: Map[K, V]

    /** Returns an observable that notifies the updates to the current state
      * table. */
    def observable: Observable[Update[K, V]]

}
