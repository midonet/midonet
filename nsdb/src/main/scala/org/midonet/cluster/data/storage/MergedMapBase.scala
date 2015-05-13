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

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Subscriber}

import org.midonet.cluster.data.storage.MergedMap.MapId
import org.midonet.util.functors.makeFunc1

/**
 * This class serves as a base class implementing the MergedMap trait.
 * The input and output observables are there to interact with the middleware
 * used to propagate opinions across the various participants. Opinions coming
 * from merged maps originating from other hosts are emitted on [[inputSubj]].
 * Opinions originating from this merged map that need to be shared with
 * participants will be emitted on [[outputSubj]]. Opinions on these subjects
 * are of the form (key, value, owner). A null value indicates the corresponding
 * opinion has been deleted.
 */
//TODO: Can we pass a bidirectional subject?
class MergedMapBase[K, V >: Null <: AnyRef]
    (mapId: MapId, owner: String, executor: ExecutorService,
     inputSubj: PublishSubject[(K, V, String)],
     outputSubj: PublishSubject[(K, V, String)])
    (implicit crStrategy: Ordering[V]) extends MergedMap[K, V] {

    /* The cache is modified when updates are emitted on [[updateObservable]]
       and therefore does need to be thread-safe since these updates will
       always occur in the same thread. */
    private val cache = new mutable.HashMap[K, mutable.HashMap[String, V]]()
    private val entryCount = new AtomicInteger(0)

    /* A map storing the winning opinion for each key. */
    private val winners = new TrieMap[K, V]()

    /* A subject emitting winning opinions due to opinion removals. That is,
       when a winning opinion is removed, the new winning opinion is
       emitted on this subject. */
    private val opinionRemovalSubject = PublishSubject.create[(K, V)]()

    /* This method takes a triple composed of a key, a value, and an owner
       and returns a key-value pair. This is used to transform [[opinionSubject]]
       into one that emits key-value pairs. */
    private val trimOwner = makeFunc1(
        (update: (K, V, String)) => (update._1, update._2)
    )

    /* A function that handles updates coming from [[opinionSubject]] and only
       lets an update pass if it results in a winning opinion change. */
    private val filterUpdate = makeFunc1((update: (K, V, String)) =>
        update match {
            // Signals the the given opinion is being deleted. Updates caused by
            // removals are notified on [[opinionRemovalSubject]].
            case (key, null, owner) =>
                onOpinionRemoval(key, owner)
                Boolean.box(false)
            case (key, value, owner) =>
                Boolean.box(onOpinionAddition(key, value, owner))
        })

    /* The scheduler on which subscriptions to [[updateStream]] and opinion
       addition/removal are scheduled. */
    private val myScheduler = Schedulers.from(executor)
    /* The subject below subscribes to [[updateObservable]] when the first
       opinion is put/removed. */
    private val updateSubject = PublishSubject.create[(K, V)]()
    /* The observable emitting winning updates made to this map. This
        observable is obtained by merging [[opinionRemovalSubject]] with
        [[inputSubj]] after having being filtered to only let
        winning opinions pass. */
    private val updateObservable: Observable[(K, V)] =
        Observable.merge[(K, V)](inputSubj.observeOn(myScheduler)
                                          .filter(filterUpdate)
                                          .map[(K, V)](trimOwner),
                                 opinionRemovalSubject)
    updateObservable subscribe updateSubject

    /* This is called when someone subscribes to the observable returned
       by method observable (which is [[updateStream]]). */
    private val onSubscribe = new OnSubscribe[(K, V)] {
        override def call(s: Subscriber[_ >: (K, V)]): Unit = {
            winners.foreach(entry => s onNext entry)
            updateSubject subscribe s
        }
    }

    /* The observable passed to the outside world. Whenever someone subscribes
       to it, we first emit the winning opinions in the map, followed by
       updates coming from [[updateObservable]]. */
    private val updateStream = Observable.create(onSubscribe)
                                         .subscribeOn(myScheduler)

    private def winningPair(opinions: mutable.HashMap[String, V])
    : (String, V) = {
        if (opinions.isEmpty) {
            (null.asInstanceOf[String], null.asInstanceOf[V])
        } else {
            opinions.maxBy[V](_._2)
        }
    }

    /* This method is called when an opinion addition is emitted on
       [[opinionSubject]] and returns true iff this results in a winning opinion
       change. */
    private def onOpinionAddition(key: K, value: V, owner: String): Boolean = {
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

    /* This method is called when an opinion removal is emitted on
       [[opinionSubject]] and emits an update on [[opinionRemovalSubject]]
       if this opinion removal results in a winning opinion change. */
    private def onOpinionRemoval(key: K, owner: String): Unit = {
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
     * @return An observable that emits the winning (key, value) pairs of this
     *         map upon subscription followed by winning updates made to this
     *         map. A null value indicates that the corresponding key has been
     *         removed.
     */
    override def observable: Observable[(K, V)] = updateStream

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
    override def containsKey(key: K): Boolean = winners.contains(key)

    /**
     * @return A snapshot of this map, where for each key, the conflict
     *         resolution strategy is used to determine the winning opinion.
     */
    override def snapshot: Map[K, V] =  winners.readOnlySnapshot.toMap

    /**
     * @return All the keys associated to this value. This method only
     *         returns keys whose values are winning opinions.
     */
    override def getByValue(value: V): List[K] =
        winners
            .filter(entry => entry._2.equals(value))
            .map(entry => entry._1)
            .toList

    /**
     * Associates the given non-null opinion to this key.
     */
    override def putOpinion(key: K, value: V): Unit =
        outputSubj onNext (key, value, owner)

    /**
     * Removes the opinion associated with this key, if any.
     */
    override def removeOpinion(key: K): Unit =
        outputSubj onNext (key, null, owner)
}
