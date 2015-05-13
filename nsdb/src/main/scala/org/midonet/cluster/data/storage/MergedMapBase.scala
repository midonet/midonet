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

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject

import org.midonet.util.functors.makeFunc1

/**
 * This class serves as a base class implementing the MergedMap trait.
 * It offers functionality that is common between the various implementations
 * of a merged map.
 */
abstract class MergedMapBase[K, V >: Null <: AnyRef]
    (implicit crStrategy: Ordering[V]) extends MergedMap[K, V]
                                       with OnSubscribe[(K, V)] {

    private val log = Logger(LoggerFactory.getLogger(this.getClass.getName))

    /* The cache is only accessed by methods triggered by updates emitted
       by observable [[inputObs]]. Therefore, it does not need to be
       thread-safe. */
    private val cache = new mutable.HashMap[K, mutable.HashMap[String, V]]()
    private val entryCount = new AtomicInteger(0)

    /* A map storing the winning opinion as well as its owner for each key. */
    private val winners = new TrieMap[K, V]()

    /* The subject emitting updates of the form (key, value, owner). This
       will be overridden by concrete subclasses. */
    val opinionObservable: Observable[(K, V, String)]
    
    /* A subject on which updates caused by opinion removals are notified. */
    private val opinionRemovalSubject = PublishSubject.create[(K, V)]()

    private val trimOwner = makeFunc1((update: (K, V, String)) =>
        (update._1, update._2)
    )

    /* A function that handles updates coming from [[opinionSubject]] and only
       lets updates pass that result in winning opinion change for some key. */
    private val filterUpdate = makeFunc1((update: (K, V, String)) =>
        update match {
            // Signals the the given opinion is being deleted. Updates caused by
            // removals are notified on [[opinionRemovalSubject]].
            case (key, null, owner) =>
                removeOpinion(key, owner)
                Boolean.box(false)
            case (key, value, owner) =>
                Boolean.box(putOpinion(key, value, owner))
            case _ =>
                log.warn("Unknown update {}", update)
                Boolean.box(false)
        })

    /* A boolean set to true once the first subscriber subscribes. When this
       happens [[updateObservable]] is built and [[updateSubject]]
       subscribes to it. */
    private val subscribed = new AtomicBoolean(false)
    /* The observable emitting winning updates made to this cache. This is
       initialized when the first owner map is merged. */
    private var updateObservable: Observable[(K, V)] = _
    /* The subject below subscribes to [[updateObservable]] when the first
       owner map is merged. */
    private val updateSubject = PublishSubject.create[(K, V)]()

    private def winningPair(opinions: mutable.HashMap[String, V]): (String, V) = {
        if (opinions.isEmpty) {
            (null.asInstanceOf[String], null.asInstanceOf[V])
        } else {
            opinions.maxBy[V](_._2)
        }
    }

    /**
     * Inserts a new opinion in the cache and returns true iff this is a
     * winning opinion.
     */
    def putOpinion(key: K, value: V, owner: String): Boolean = {
        if (!cache.contains(key)) {
            cache.put(key, mutable.HashMap[String, V]())
            entryCount.incrementAndGet()
        } else if (cache(key).isEmpty) {
            entryCount.incrementAndGet()
        }
        // We never remove entries from the map, and map(key) can never be null.
        cache(key) += owner -> value

        // If this is a winning opinion, we update the winners map.
        if (winningPair(cache(key)) == (owner, value)) {
            winners.put(key, value)
            true
        } else {
            false
        }
    }

    /**
     * Removes the opinion of the given owner.
     */
    def removeOpinion(key: K, owner: String): Unit = {
        cache.get(key) match {
            case Some(opinions) =>
                val (prevOwner, prevValue) = winningPair(opinions)

                // The size method called by another thread can temporarily
                // give a result that is off by one (before we decrement
                // entryCount). This should be tolerated.
                opinions.retain((o, v) => !o.equals(owner))
                if (opinions.isEmpty && prevValue != null) {
                    entryCount.decrementAndGet()
                }

                val (newOwner, newValue) = winningPair(opinions)
                // The winning opinion for the key has changed, update the
                // winners maps and emit the new winning opinion.
                if (!(prevOwner, prevValue).equals((newOwner, newValue))) {
                    if (newValue != null) {
                        winners.put(key, newValue)
                    } else {
                        winners.remove(key)
                    }
                    opinionRemovalSubject onNext (key, newValue)
                }

            case _ =>
        }
    }

    /**
     * Merges the owner map with this merged map. Any subclass must override
     * this method and call the method below before anything else.
     * @return True if the operation is successful and false if the given
     *         owner map already merged into this map.
     */
    override def merge(ownerMap: OwnerMap[K, V]): Boolean = {
        if (subscribed.compareAndSet(false, true)) {
            updateObservable = Observable.merge[(K, V)](
                opinionObservable.filter(filterUpdate)
                    .map[(K, V)](trimOwner),
                opinionRemovalSubject)
            updateObservable subscribe updateSubject
        }
        true
    }

    /**
     * Removes the owner map from this merged map. Any subclass must override
     * this method and call the method below before anything else.
     * @return True if the operation is successful and false if this owner
     *         map was not previously merged into this map.
     */
    override def unmerge(ownerMap: OwnerMap[K, V]): Boolean = {
        val owner = ownerMap.owner
        for (key <- cache.keySet) {
            removeOpinion(key, owner)
        }
        true
    }

    /* This method is called when an observer subscribes to the observable
       below. */
    // TODO: how do you ensure that entries added between the snapshot and
    //       the subject.subscribe are not missed?
    override def call(s: Subscriber[_ >: (K, V)]): Unit = {
        for (entry <- winners.readOnlySnapshot) {
            s onNext entry
        }
        updateSubject subscribe s
    }

    /**
     * @return An observable that emits the content of this map upon subscription
     *         followed by updates to this map. The underlying conflict
     *         resolution strategy is used to determine the winning opinion when
     *         more than one value exists for a given key in the
     *         various owner maps. A null value indicates that the corresponding
     *         key has been removed.
     */
    override def observable: Observable[(K, V)] = Observable.create(this)

    /**
     * @return The winning opinion associated to this key.
     */
    override def get(key: K): V = winners.get(key).orNull

    /**
     * @return The number of entries in this map.
     */
    override def size = entryCount.get

    /**
     * @return True iff this cache contains the given key.
     */
    override def containsKey(key: K): Boolean = {
        winners.get(key) match {
            case Some(value) => true
            case _ => false
        }
    }

    /**
     * @return A snapshot of this map, where for each key, the conflict
     *         resolution strategy is used to determine the winning opinion.
     */
    override def snapshot: Map[K, V] =  winners.readOnlySnapshot.toMap
}
