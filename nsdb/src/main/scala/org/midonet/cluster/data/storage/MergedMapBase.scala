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
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster.data.storage.MergedMap.MapId
import org.midonet.util.functors.makeAction1

/**
 * This class serves as a base class implementing the MergedMap trait.
 * Concrete implementations of the MergedMap trait subclass this class
 * and implement the [[opinionObservable]] and [[opinionObserver]] methods.
 *
 * The observable and observer returned by these methods are there to interact
 * with the middleware used to propagate opinions among the various
 * participants. Opinions coming from merged maps are emitted on
 * [[opinionObservable]]. Opinions originating from this merged map that need to
 * be shared with participants will be emitted on [[opinionObserver]]. Opinions
 * are of the form (key, value, owner). A null value indicates that the
 * corresponding opinion has been deleted.
 */
abstract class MergedMapBase[K, V >: Null <: AnyRef]
    (mapId: MapId, owner: String, executor: ExecutorService)
    (implicit crStrategy: Ordering[V]) extends MergedMap[K, V] {

    /* The cache is always modified from within [[myScheduler]] and therefore
       does not need to be thread-safe. */
    private val cache = new mutable.HashMap[K, mutable.HashMap[String, V]]()
    private val entryCount = new AtomicInteger(0)

    /* A map storing the winning opinion for each key. */
    private val winners = new TrieMap[K, V]()

    /* The scheduler on which subscriptions to [[updateStream]] and opinion
       addition/removal are scheduled. */
    private val myScheduler = Schedulers.from(executor)

    /* The subject on which winning opinions are emitted. */
    private val winningSubject = PublishSubject.create[(K, V)]()

    /* This method handles opinions emitted on [[opinionObservable]]. */
    private def onNewOpinion(opinion: (K, V, String)): Unit = opinion match {
        // Signals the the given opinion is being deleted.
        case (key, null, owner) =>
            onOpinionRemoval(key, owner)
        case (key, value, owner) =>
            onOpinionAddition(key, value, owner)
    }

    opinionObservable.observeOn(myScheduler)
                     .subscribe(makeAction1(onNewOpinion))

    /* This is called when someone subscribes to the observable returned
       by method observable (which is [[updateStream]]). */
    private val onSubscribe = new OnSubscribe[(K, V)] {
        override def call(s: Subscriber[_ >: (K, V)]): Unit = {
            winners.foreach(entry => s onNext entry)
            winningSubject subscribe s
        }
    }

    /* The observable passed to the outside world. Whenever someone subscribes
       to it, we first emit the winning opinions in the map, followed by
       updates coming from [[winningSubject]]. */
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

    private def onOpinionAddition(key: K, value: V, owner: String): Unit = {
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
            winningSubject onNext (key, value)
        }
    }

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
                    winningSubject onNext (key, newValue)
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
        winners.filter(entry => entry._2.equals(value))
               .map(entry => entry._1)
               .toList

    /**
     * @return The observable emitting opinions of the form (key, value, owner)
     *         coming from the participants of this merged map.
     */
    protected def opinionObservable: Observable[(K, V, String)]

    /**
     * @return An observer to which opinions coming from this map will be
     *         emitted and propagated to participants of this map.
     */
    protected def opinionObserver: Observer[(K, V, String)]

    /**
     * Associates the given non-null opinion to this key.
     */
    override def putOpinion(key: K, value: V): Unit =
        opinionObserver onNext (key, value, owner)

    /**
     * Removes the opinion associated with this key, if any.
     */
    override def removeOpinion(key: K): Unit =
        opinionObserver onNext (key, null, owner)
}
