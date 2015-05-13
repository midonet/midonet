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

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import rx.Observable
import rx.subjects.PublishSubject

private[storage] trait WriteableMergedMap[K, D <: Any, V <: AnyRef] {
    def putOpinion(ownerId: String, key: K, discriminant: D, value: V): Unit
    def removeOpinion(ownerId: String, key: K): Unit
}

/**
 * An in-memory and thread-safe implementation of a MergedMap. Despite the fact
 * that this class is thread-safe, public methods are not necessarily executed
 * atomically. For instance, it may happen that a call to size returns x
 * and a subsequent call to snapshot (without any other calls in between)
 * returns a map with x-1 or x+1 entries.
 */
//TODO: Fix race conditions such as obtaining a winning opinion and retrieving
//      the corresponding value non-atomically in winningPair(...).
class InMemoryMergedMap[K, D <: Any, V <: AnyRef]
    (implicit val crStrategy: Ordering[D]) extends MergedMap[K, D, V]
                                           with WriteableMergedMap[K, D, V] {

    private val map = new TrieMap[K, TrieMap[D, (V, String)]]()
    private val entryCount = new AtomicInteger(0)
    private val owners = TrieMap[String, InMemPrivateMap[K, D, V]]()

    private val subject = PublishSubject.create[(K, V)]()

    private def winner(opinions: TrieMap[D, (V, String)]): V =
        winningPair(opinions)._2

    private def winningPair(opinions: TrieMap[D, (V, String)])
    : (D, V) = {
        if (opinions.isEmpty) {
            (null.asInstanceOf[D], null.asInstanceOf[V])
        } else {
            val discriminant = opinions.keySet.max
            (discriminant, opinions(discriminant)._1)
        }
    }

    private def removeOpinionsFromOwner(ownerId: String, key: K,
                                        opinions: TrieMap[D, (V, String)])
    : Unit = {
        val (prevD, prevV) = winningPair(opinions)

        // The size method called by another thread can temporarily
        // give a result that is off by one (before we decrement
        // entryCount). This should be tolerated.
        opinions.retain((d: D, v: (V, String)) => !v._2.equals(ownerId))
        if (opinions.isEmpty) {
            entryCount.decrementAndGet()
        }

        val (curD, curV) = winningPair(opinions)
        if (!(prevD, prevV).equals((curD, curV))) {
            subject.onNext((key, curV))
        }
    }

    /**
     * @return True iff this map contains the given key.
     */
    override def containsKey(key: K): Boolean = {
        map.get(key) match {
            case Some(opinions) => winner(opinions) != null
            case _ => false
        }
    }

    /**
     * @return A snapshot of this map, where for each key, the conflict
     *         resolution strategy is used to determine the winning opinion.
     */
    override def snapshot: Map[K, V] = {
        val snapshot = mutable.HashMap[K, V]()

        for ((key, opinions) <- map) {
            val winning = winner(opinions)
            if (winning != null) {
                snapshot(key) = winning
            }
        }
        snapshot.toMap
    }

    /**
     * @return The winning opinion associated to this key.
     */
    override def get(key: K): V = map.get(key) match {
        case Some(opinions) =>
             winner(opinions)

        case _ => null.asInstanceOf[V]
    }

    /**
     * @return An observable that emits the content of this map upon subscription
     *         followed by updates to this map. The underlying conflict
     *         resolution strategy is used to determine the winning opinion when
     *         more than one value exists for a given key in the
     *         various private maps. A null value indicates that the corresponding
     *         key has been removed.
     */
    override def observable: Observable[(K, V)] =
        subject.asObservable.startWith(Observable.from(snapshot.toArray))

    /**
     * Returns a new private map for the given owner. The returned private map
     * will be merged with this merged map. If the given owner already joined
     * this map, then we return the previously created private map.
     */
    override def join(ownerId: String): PrivateMap[K, D, V] = {
        // TODO: use the observable of the private map to do the merging.
        owners.get(ownerId) match {
            case Some(privateMap) => privateMap
            case _ =>
                val privateMap = new InMemPrivateMap[K, D, V](ownerId, this)
                owners.put(ownerId, privateMap)
                privateMap
        }
    }

    /**
     * Removes the private map of the given owner from this merged map.
     */
    override def leave(ownerId: String): Unit = {
        owners.remove(ownerId) match {
            case Some(privateMap) =>
                for ((key, opinions: TrieMap[D, (V, String)]) <- map) {
                    removeOpinionsFromOwner(ownerId, key, opinions)
                }
            case _ => throw new IllegalStateException("Owner: " + ownerId +
                        " is not a member of this merged map")
        }
    }

    /**
     * @return The number of entries in this map.
     */
    override def size: Int = entryCount.get

    /**
     * @return All the keys associated to this value in descending order. This
     *         method only returns keys whose values are winning opinions.
     */
    override def getByValue(value: V): List[K] = {
        val keys = mutable.MutableList[(D, K)]()

        // Collect the keys associated to the given value along with their
        // discriminant. Filter out values that are not winning opinions.
        for (key <- map.keySet) {
            map.get(key) match {
                case Some(opinions: TrieMap[D, (V, String)]) =>
                    val (d, v) = winningPair(opinions)
                    if (v != null && v.equals(value)) {
                        keys += ((d, key))
                    }
                case _ =>
            }
        }

        // Sort according to discriminant order and return the sorted list
        // of keys.
        keys.sorted(Ordering.by((el: (D, K)) => el._1).reverse)
            .map(_._2)
            .toList
    }
    
    /* Methods from the writeable merged map trait */
    def putOpinion(ownerId: String, key: K, discriminant: D, value: V): Unit = {
        if (owners.contains(ownerId)) {
            // The size method called by another thread can temporarily give a
            // result that is off by one (before we increment entryCount).
            // This should be tolerated.
            map.putIfAbsent(key, TrieMap[D, (V, String)]()) match {
                case None => entryCount.incrementAndGet()
                case _ =>
            }
            map.get(key) match {
                case Some(opinions) =>
                    opinions.put(discriminant, (value, ownerId))

                    // If this is a winning opinion, then emit an update
                    // on the subject.
                    if (winningPair(opinions) == (discriminant, value)) {
                        subject.onNext((key, value))
                    }
                case None =>
                    throw new IllegalStateException("Adding opinion to private" +
                        " map of owner: " + ownerId + " failed")
            }
        } else {
            throw new IllegalStateException("Owner: " + ownerId + " is not " +
                "authorized to put opinions because it left the merged map")
        }
    }

    def removeOpinion(ownerId: String, key: K): Unit = {
        if (owners.contains(ownerId)) {
            map.get(key) match {
                case Some(opinions) =>
                    removeOpinionsFromOwner(ownerId, key, opinions)
                case _ =>
            }
        } else {
            throw new IllegalStateException("Owner: " + ownerId + " is not " +
                "authorized to remove opinions because it left the merged map")
        }
    }
    /* End of writeable merged map methods */
}

/**
 * An in-memory implementation of the PrivateMap trait. It is assumed that
 * a single-thread will call methods of this class.
 */
class InMemPrivateMap[K, D <: Any, V <: AnyRef] private[storage]
    (ownerId: String, mergedMap: WriteableMergedMap[K, D, V])

    extends PrivateMap[K, D, V] {

    private val map = mutable.HashMap[K, (D, V)]()
    private val subject = PublishSubject.create[(K, D, V)]()

    /**
     * @return True iff this map contains the given key.
     */
    override def containsKey(key: K): Boolean = map.contains(key)

    /**
     * @return A snapshot of this map.
     */
    override def snapshot: Map[K, (D, V)] = {
        val snap = mutable.HashMap[K, (D, V)]()
        for ((k, (d, v)) <- map) {
            snap.put(k, (d, v))
        }
        snap.toMap
    }

    /**
     * @return An observable that emits the content of this private map upon
     *         subscription followed by updates to this map. A null value
     *         indicates that the corresponding key has been removed.
     */
    override def observable: Observable[(K, D, V)] =
        subject.asObservable.startWith(Observable.from(snapshot.map(el =>
                                           (el._1, el._2._1, el._2._2)).asJava))

    /**
     * @return The owner of this private map.
     */
    override def owner: String = ownerId

    /**
     * @return The number of entries in this map.
     */
    override def size: Int = map.size

    /**
     * Associates the given opinion to this key. If this key was already
     * inserted in this private map, then the value will be overwritten.
     * Multiple values associated to the same key can co-exist provided that
     * they are inserted in different private maps.
     *
     * @return The previous opinion associated to this key and in this private
     *         map, or null if no such opinion exists.
     */
    override def putOpinion(key: K, discriminant: D, value: V): Unit = {
        map.put(key, (discriminant, value))
        mergedMap.putOpinion(ownerId, key, discriminant, value)
    }

    /**
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    override def removeOpinion(key: K): Unit = {
        map.remove(key)
        mergedMap.removeOpinion(ownerId, key)
    }

    /**
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    override def getOpinion(key: K): V = map.get(key) match {
        case Some((d, v)) => v
        case _ => null.asInstanceOf[V]
    }
}