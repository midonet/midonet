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

import scala.collection.concurrent.TrieMap

import rx.Observable
import rx.subjects.PublishSubject

object InMemoryMergedMap {
    /**
     * Stores the state for an owner map and exposes an observable for it.
     * If the owner associated with this owner map leaves the merged map,
     * we unsubscribe from it by calling the complete() method below.
     */
    private final class OwnerMapState[K, V >: Null <: AnyRef]
        (ownerMap: OwnerMap[K, V]) {

        private val mark = PublishSubject.create[(K, V, String)]()
        val observable = ownerMap.observable.takeUntil(mark)

        /** Completes the observable corresponding to this owner map state. */
        def complete() = mark.onCompleted()
    }
}

/**
 * An in-memory and thread-safe implementation of a MergedMap. Despite the fact
 * that this class is thread-safe, public methods are not necessarily executed
 * atomically. For instance, it may happen that a call to size returns x
 * and a subsequent call to snapshot (without any other calls in between)
 * returns a map with x-1 or x+1 entries. In other words, this implementation
 * is serializable (or sequentially consistent) but not linearizable:
 * it corresponds to *some* sequential execution where operations can be
 * re-ordered.
 */
class InMemoryMergedMap[K, V >: Null <: AnyRef]
    (implicit crStrategy: Ordering[V]) extends MergedMapBase[K, V] {

    import InMemoryMergedMap._

    /* The owner maps that merged into this map. */
    private val owners = TrieMap[String, OwnerMap[K, V]]()
    private val ownerMaps = TrieMap[String, OwnerMapState[K, V]]()

    /* Whenever an owner map merges with this merged map, the owner map
       observable is notified on this subject. */
    private val ownerMapsSubject =
        PublishSubject.create[Observable[(K, V, String)]]()

    /* The opinion observable on which updates of the form (key, value, owner)
       will be emitted. This is the input observable used in the
       super class. */
    override val opinionObservable =
        Observable.merge[(K, V, String)](ownerMapsSubject)

    /**
     * Merges the owner map with this merged map.
     * @return True if the operation is successful and false if the given
     *         owner map already merged into this map.
     */
    override def merge(ownerMap: OwnerMap[K, V]): Boolean = {
        super.merge(ownerMap)

        owners.get(ownerMap.owner) match {
            case Some(_) => false
            case _ =>
                val ownerMapState = new OwnerMapState[K, V](ownerMap)
                owners.put(ownerMap.owner, ownerMap)
                ownerMaps.put(ownerMap.owner, ownerMapState)
                ownerMapsSubject onNext ownerMapState.observable
                true
        }
    }

    /**
     * Removes the owner map from this merged map.
     * @return True if the operation is successful and false if this owner
     *         map was not previously merged into this map.
     */
    override def unmerge(ownerMap: OwnerMap[K, V]): Boolean = {
        super.unmerge(ownerMap)

        val owner = ownerMap.owner
        owners.remove(ownerMap.owner) match {
            case Some(_) =>
                ownerMaps.remove(owner) match {
                    case Some(ownerMapState) =>
                        ownerMapState.complete()
                        true

                    case _ => false
                }
            case _ => false
        }
    }

    /**
     * @return All the keys associated to this value. This method only
     *         returns keys whose values are winning opinions.
     */
    override def getByValue(value: V): List[K] =
        snapshot
            .filter(entry => entry._2.equals(value))
            .map(entry => entry._1)
            .toList
}

