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

object MergedMap {
    trait MapId {
        def toString: String
    }
}

/**
 * This trait exposes the interface of a MergedMap. A merged map is a read-only
 * map that allows clients to obtain the "merged" view of the various owner maps
 * that joined this map. The merged view is obtained by comparing the different
 * values, also denoted as opinions, associated with each key. This comparison
 * elects the winning opinion using a provided conflict resolution strategy.
 */
trait MergedMap[K, V >: Null <: AnyRef] {
    /**
     * Merges the owner map with this merged map.
     * @return True if the operation is successful and false if the given
     *         owner map already merged into this map.
     */
    def merge(map: OwnerMap[K, V]): Boolean

    /**
     * Removes the owner map from this merged map.
     * @return True if the operation is successful and false if this owner
     *         map was not previously merged into this map.
     */
    def unmerge(map: OwnerMap[K, V]): Boolean

    /**
     * @return An observable that emits the content of this map upon
     *         subscription followed by updates to this map. The underlying
     *         conflict resolution strategy is used to determine the winning
     *         opinion when more than one value exists for a given key in the
     *         various owner maps. A null value indicates that the
     *         corresponding key has been removed.
     */
    def observable: Observable[(K, V)]

    /**
     * @return True iff this map contains the given key.
     */
    def containsKey(key: K): Boolean

    /**
     * @return The number of entries in this map.
     */
    def size: Int

    /**
     * @return The winning opinion associated to this key.
     */
    def get(key: K): V

    /**
     * @return All the keys associated to this value. This method only
     *         returns keys whose values are winning opinions.
     */
    def getByValue(value: V): List[K]

    /**
     * @return A snapshot of this map, where for each key, the conflict
     *         resolution strategy is used to determine the winning opinion.
     */
    def snapshot: Map[K, V]
}
