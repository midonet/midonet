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

/**
 * This class represents a map belonging to a specific owner. Each owner
 * can associate a value with a given key. Such value is denoted as the opinion
 * of the owner for that key (this opinion cannot be null).
 */
trait OwnerMap[K, V >: Null <: AnyRef] {
    /**
     * Returns the owner of this map.
     */
    def owner: String

    /**
     * Associates the given non-null opinion to this key.
     *
     * @return The previous opinion associated to this key and in this owner
     *         map, or null if no such opinion exists.
     */
    def putOpinion(key: K, value: V): V

    /**
     * Removes the opinion associated with this key, if any.
     * @return The opinion previously put in this owner map and associated to
     *         this key, or null if no such opinion exists.
     */
    def removeOpinion(key: K): V

    /**
     * @return The opinion previously put in this owner map and associated to
     *         this key, or null if no such opinion exists.
     */
    def getOpinion(key: K): V

    /**
     * @return An observable that emits the content of this map upon
     *         subscription followed by updates to this map. Updates are of the
     *         form (key, value, owner). A null value indicates that the
     *         corresponding key has been removed.
     */
    def observable: Observable[(K, V, String)]

    /**
     * @return True iff this map contains the given key.
     */
    def containsKey(key: K): Boolean

    /**
     * @return The number of entries in this map.
     */
    def size: Int

    /**
     * @return A snapshot of this map.
     */
    def snapshot: Map[K, V]
}