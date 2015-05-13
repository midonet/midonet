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

sealed trait MapValue[D, V] extends Comparable[D] {
    def discriminant: D
    def value: V
}

/**
 * This class represents a map belonging to a specific owner. Each owner
 * can associate a value with a given key. Such value is denoted as the opinion
 * of the owner for that key. Different opinions for the same key can co-exist
 * provided that they are put in different private maps. A merged view of these
 * private maps can be obtained from a class implementing the MergedMap
 * trait below.
 */
trait PrivateMap[K, D <: Any, V <: AnyRef] {

    /**
     * @return The owner of this private map.
     */
    def owner: String

    /**
     * Associates the given opinion to this key. If this key was already
     * inserted in this private map, then the value will be overwritten.
     * Multiple values associated to the same key can co-exist provided that
     * they are inserted in different private maps.
     */
    def putOpinion(key: K, discriminant: D, value: V): Unit

    /**
     * Removes the opinion associated with this key, if any.
     */
    def removeOpinion(key: K): Unit

    /**
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    def getOpinion(key: K): V

    /**
     * @return An observable that emits the content of this private map upon
     *         subscription followed by updates to this map. A null value
     *         indicates that the corresponding key has been removed.
     */
    def observable: Observable[(K, D, V)]

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
    def snapshot: Map[K, (D, V)]
}

object MergedMap {
    trait MapId
}

/**
 * This trait exposes the interface of a MergedMap. A merged map is a read-only
 * map that allows clients to obtain the "merged" view of the various private
 * maps defined above. The merged view is obtained by comparing the different
 * values, also denoted as opinions, associated with each key. As such, the
 * class V of map values must implement the comparable interface.
 */
trait MergedMap[K, D <: Any, V <: AnyRef] {

    /**
     * Returns a new private map for the given owner. The returned private map
     * will be merged with this merged map. If the given owner already joined
     * this map, then we return the previously created private map.
     */
    def join(owner: String): PrivateMap[K, D, V]

    /**
     * Removes the private map of the given owner from this merged map.
     */
    def leave(owner: String): Unit

    /**
     * @return An observable that emits the content of this map upon subscription
     *         followed by updates to this map. The underlying conflict
     *         resolution strategy is used to determine the winning opinion when
     *         more than one value exists for a given key in the
     *         various private maps. A null value indicates that the corresponding
     *         key has been removed.
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
     * @return All the keys associated to this value in descending order. This
     *         method only returns keys whose values are winning opinions.
     */
    def getByValue(value: V): List[K]

    /**
     * @return A snapshot of this map, where for each key, the conflict
     *         resolution strategy is used to determine the winning opinion.
     */
    def snapshot: Map[K, V]
}

