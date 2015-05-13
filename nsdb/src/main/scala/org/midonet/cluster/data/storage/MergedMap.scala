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

object PrivateMap {
    trait OwnerId
}

/**
 * This class represents a map belonging to a specific owner. Each owner
 * can associate a value with a given key. Such value is denoted as the opinion
 * of the owner for that key. Different opinions for the same key can co-exist
 * provided that they are put in different private maps. A merged view of these
 * private maps can be obtained from a class implementing the MergedMap
 * trait below.
 */
trait PrivateMap[K, V <: Comparable[V]] {

    import MergedMap._
    import PrivateMap._

    protected val ownerId: OwnerId

    /** The merged map this private map belongs to. */
    protected val mapId: MapId

    /**
     * @return The owner of this private map.
     */
    def owner: OwnerId

    /**
     * Associates the given opinion to this key. If this key was already
     * inserted in this private map, then the value will be overwritten.
     * Multiple values associated to the same key can co-exist provided that
     * they are inserted in different private maps.
     *
     * @return The previous opinion associated to this key and in this private
     *         map, or null if no such opinion exists.
     */
    def putOpinion(key: K, value: V): V

    /**
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
     * @return An observable that emits the content of this private map upon
     *         subscription followed by updates to this map.
     */
    def observable: Observable[MapUpdate]

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

object MergedMap {
    trait MapId

    sealed trait MapUpdate
    case class EntryAdded(key: AnyRef, value: AnyRef) extends MapUpdate
    case class EntryDeleted(key: AnyRef) extends MapUpdate
}

/**
 * This trait exposes the interface of a MergedMap. A merged map is a read-only
 * map that allows clients to obtain the "merged" view of the various private
 * maps defined above. The merged view is obtained by comparing the different
 * values, also denoted as opinions, associated with each key. As such, the
 * class V of map values must implement the comparable interface.
 */
trait MergedMap[K, V <: Comparable[V]] {

    import MergedMap._

    protected val mapId: MapId

    /**
     * Includes the given private map into this merged map.
     */
    def merge(map: PrivateMap[K, V]): Unit

    /**
     * Removes the given private map from this merged map.
     */
    def unmerge(map: PrivateMap[K, V]): Unit

    /**
     * @return An observable that emits the content of this map upon subscription
     *         followed by updates to this map. The underlying conflict
     *         resolution strategy is used to determine the winning opinion when
     *         more than one value exists for a given key in the
     *         various private maps.
     */
    def observable: Observable[MapUpdate]

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
     * @return All the keys associated to this value in descending order.
     */
    def getByValue(value: V): List[K]

    /**
     * @return A snapshot of this map, where for each key, the conflict
     *         resolution strategy is used to determine the winning opinion.
     */
    def snapshot: Map[K, V]
}

