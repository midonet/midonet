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

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import rx.Observable

/**
 * An in-memory implementation of a MergedMap.
 */
class InMemoryMergedMap[K, D <: Any, V <: AnyRef]
    (implicit val crStrategy: Ordering[D]) extends MergedMap[K, D, V] {

    private val map = new TrieMap[K, TrieMap[D, (V, String)]]()
    private val entryCount = new AtomicInteger(0)

    private def winner(opinions: TrieMap[D, (V, String)]): V = {
        val winningKey = opinions.keySet.max[D]
        opinions(winningKey)._1
    }

    /**
     * @return True iff this map contains the given key.
     */
    override def containsKey(key: K): Boolean = {
        map.get(key) match {
            case Some(opinions) =>
                !opinions.isEmpty && (winner(opinions) != null)
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
    override def get(key: K): V = winner(map(key))

    /**
     * @return An observable that emits the content of this map upon subscription
     *         followed by updates to this map. The underlying conflict
     *         resolution strategy is used to determine the winning opinion when
     *         more than one value exists for a given key in the
     *         various private maps.
     */
    override def observable: Observable[(K, V)] = ???

    /**
     * Returns a new private map for the given owner. The returned private map
     * will be merged in this merged map.
     */
    override def join(owner: String): PrivateMap[K, D, V] =
        new InMemPrivateMap[K, D, V](owner, map, entryCount)

    /**
     * Removes the private map of the given owner from this merged map.
     */
    override def leave(ownerId: String): Unit = {
        for ((key, trieMap: TrieMap[D, (V, String)]) <- map) {
            trieMap.retain((d: D, v: (V, String)) => !v._2.equals(ownerId))
        }
    }

    /**
     * @return The number of entries in this map.
     */
    override def size: Int = entryCount.get

    /**
     * @return All the keys associated to this value in descending order.
     */
    override def getByValue(value: V): List[K] = {
        val keys = mutable.MutableList[(D, K)]()

        // Collect the keys associated to the given value along with their
        // discriminant.
        for (key <- map.keySet) {
            map(key)
                .filter((el: (D, (V, String))) => el._2._1.equals(value))
                .foreach((el: (D, (V, String))) => keys += ((el._1, key)))
        }

        // Sort according to discriminant order and return the sorted list
        // of keys.
        keys.sortBy((el: (D, K)) => el._1).map((el: (D, K)) => el._2).toList
    }
}

class InMemPrivateMap[K, D <: Any, V <: AnyRef] private[storage]
    (ownerId: String, mergedMap: TrieMap[K, TrieMap[D, (V, String)]],
     entryCount: AtomicInteger)

    extends PrivateMap[K, D, V] {

    private val map = mutable.HashMap[K, V]()

    /**
     * @return True iff this map contains the given key.
     */
    override def containsKey(key: K): Boolean = ???

    /**
     * @return A snapshot of this map.
     */
    override def snapshot: Map[K, V] = {
        val snap = mutable.HashMap[K, V]()
        for ((key, value) <- map) {
            snap.put(key, value)
        }
        snap.toMap
    }

    /**
     * @return An observable that emits the content of this private map upon
     *         subscription followed by updates to this map.
     */
    override def observable: Observable[(K, V)] = ???

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
        // Putting the opinion in the private map
        map.put(key, value)

        // Merging the opinion into the merged map
        mergedMap.putIfAbsent(key, TrieMap[D, (V, String)]()) match {
            case None => entryCount.incrementAndGet()
            case _ =>
        }
        mergedMap.get(key) match {
            case Some(trieMap) =>
                trieMap.put(discriminant, (value, ownerId))
            case None =>
                throw new IllegalStateException("Adding opinion to private" +
                                                " map of owner: " + ownerId +
                                                " failed")
        }
    }

    /**
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    override def removeOpinion(key: K): Unit = {
        map.remove(key)

        mergedMap(key) match {
            case trieMap: TrieMap[D, (V, String)] =>
                trieMap.retain((d: D, v: (V, String)) => !v._2.equals(ownerId))
                if (trieMap.isEmpty) {
                    entryCount.decrementAndGet()
                }
            case _ =>
        }
    }

    /**
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    override def getOpinion(key: K): V = map(key)
}