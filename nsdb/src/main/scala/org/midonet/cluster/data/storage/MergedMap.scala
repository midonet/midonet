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
 * This trait exposes the interface of a MergedMap. A merged map allows several
 * values to be associated to the same key. These values are called opinions
 * and each opinion belongs to a specific owner. The merged map relies on a
 * conflict resolution strategy to select a winning opinion for each key.
 */
trait MergedMap[K, V >: Null <: AnyRef] {
    /**
     * Associates the given non-null opinion to this key.
     */
    def putOpinion(key: K, value: V): Unit

    /**
     * Removes the opinion associated with this key, if any.
     */
    def removeOpinion(key: K): Unit

    /**
     * @return An observable that emits the winning (key, value) pairs of this
     *         map upon subscription followed by winning updates made to this
     *         map. A null value indicates that the corresponding key has been
     *         removed.
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
