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

object SharedMap {
    /**
     * An enumeration of available conflict resolution strategies.
     */
    object CRStrategy extends Enumeration {
        type CRStrategy = Value
        val LWW = Value /* last writer wins */
    }

    trait MapUpdate
    case class EntryAdded(key: AnyRef, value: AnyRef) extends MapUpdate
    case class EntryDeleted(key: AnyRef) extends MapUpdate
}

/**
 * This trait exposes the interface of a SharedMap. A shared map allows
 * "owners" to write a different value for the same key, such values are denoted
 * as "opinions". A conflict resolution strategy is used to determine the
 * winning opinion.
 *
 * Different opinions for the same key can co-exist provided that they are put
 * in the map by different owners. A put by some owner for a given key will
 * overwrite the previous value, if any.
 */
trait SharedMap[K, V] {

    import SharedMap._
    import SharedMap.CRStrategy._

    protected val mapId: UUID
    protected val ownerId: UUID
    protected val crStrategy: CRStrategy

    /**
     * Disables caching of the shared map.
     */
    def stopCache(): Unit

    /**
     * @return An observable emitting updates to this map. The underlying
     * conflict resolution strategy is used to determine the winning opinion
     * when more than one value exists for a given key.
     */
    def observable(): Observable[MapUpdate]

    /**
     * @return True iff this map contains the given key.
     */
    def containsKey(key: K): Boolean

    /**
     * @return The number of entries in this map.
     */
    def getSize: Int

    /**
     * @return The winning opinion associated with this key.
     */
    def get(key: K): V

    /**
     * @return All the keys associated to this value.
     */
    def getByValue(value: V): List[K]

    /**
     * @return A snapshot of this map, where for each key, the conflict
     * resolution strategy is used to determine the winning opinion.
     */
    def getMap: Map[K, V]

    /**
     * Associates the given opinion to this key. If this key was already
     * inserted by the same owner, then the value will be overwritten. Multiple
     * values associated with the same key can co-exist provided that they are
     * inserted by different owners
     *
     * @return The previous opinion associated to this key and inserted by this
     *         owner, or null if no such opinion exists.
     */
    def putOpinion(key: K, value: V): V

    /**
     * @return The opinion previously put by this owner and associated to
     *         this key, or null if no such opinion exists.
     */
    def removeOpinion(key: K): V

    /**
     * @return The opinion previously put by this owner and associated with this
     *         key, or null if no such opinion exists.
     */
     def getOpinion(key: K): V
}
