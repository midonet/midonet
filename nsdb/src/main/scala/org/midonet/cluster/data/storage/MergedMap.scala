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
 * of the owner for that key (this opinion cannot be null). A private map
 * instance can be obtained from a join call belonging to the MergedMap trait
 * below.
 *
 * K is the class of the key, D is the class of the discriminant associated with
 * each opinion (and used to determine the winning one in a MergedMap), and
 * V is the class of the value.
 */
trait PrivateMap[K, D <: Any, V <: AnyRef] {
    /**
     * Associates the given non-null opinion to this key.
     *
     * @return The previous opinion associated to this key and in this private
     *         map, or null if no such opinion exists.
     */
    def putOpinion(key: K, discriminant: D, value: V): V

    /**
     * Removes the opinion associated with this key, if any.
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    def removeOpinion(key: K): V

    /**
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    def getOpinion(key: K): V

    /**
     * @return An observable that emits updates to this map. A null value
     *         indicates that the corresponding key has been removed.
     */
    def observable: Observable[(K, D, V, String)]

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
    trait MapId {
        def toString: String
    }
}

/**
 * This trait exposes the interface of a MergedMap. A merged map is a read-only
 * map that allows clients to obtain the "merged" view of the various private
 * maps defined above. The merged view is obtained by comparing the different
 * values, also denoted as opinions, associated with each key. The discriminant
 * associated with each opinion is used to determine the winning opinion. As
 * such an implementation of MergedMap will assume an ordering on instances
 * of class D.
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
     * @return An observable that emits the content of this map upon
     *         subscription followed by updates to this map. The underlying
     *         conflict resolution strategy is used to determine the winning
     *         opinion when more than one value exists for a given key in the
     *         various private maps. A null value indicates that the
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

