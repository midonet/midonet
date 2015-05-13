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
import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.{Observable, Subscriber}

import org.midonet.util.functors._

object InMemoryMergedMap {
    /**
     * Stores the state for a private map and exposes an observable for it.
     * If the owner associated with this private map leaves the merged map,
     * we unsubscribe from it by calling the complete() method below.
     */
    private final class PrivateMapState[K,D <: Any, V <: AnyRef]
        (privateMap: PrivateMap[K, D, V]) {

        private val mark = PublishSubject.create[(K, D, V, String)]()
        val observable = privateMap.observable.takeUntil(mark)

        /** Completes the observable corresponding to this private map state. */
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
class InMemoryMergedMap[K, D <: Any, V <: AnyRef]
    (implicit val crStrategy: Ordering[D]) extends MergedMap[K, D, V]
                                           with OnSubscribe[(K, V)] {

    import InMemoryMergedMap._

    private val log = Logger(LoggerFactory.getLogger(this.getClass.getName))
    private val map = new TrieMap[K, TrieMap[D, (V, String)]]()
    private val entryCount = new AtomicInteger(0)
    private val owners = TrieMap[String, InMemoryPrivateMap[K, D, V]]()

    /* The subject on which observables for joining private maps are notified. */
    private val privateMapSubject =
        PublishSubject.create[Observable[(K, D, V, String)]]()
    /* The subject on which updates due to opinion removals are notified. */
    private val opinionRemovalSubject = PublishSubject.create[(K, V)]()

    /*
       [[updatesObservable]] is the observable combining updates due to
       insertion and removals of opinions in the private maps. This observable
       merges private map observables emitted on the privateMapSubject and
       updates coming from the opinionRemovalSubject. Updates coming from
       private map observables are filtered in the case the merged view does not
       change. More precisely, the filter function determines, in the case of an
       insertion, whether the inserted opinion is winning. In the positive,
       the update passes through. In the case of an opinion removal,
       the filter function emits an update on the opinionRemovalSubject
       only if the removed opinion was a winning one. Here is a schematic view
       of this observable:

       join(ownerId) ---> privateMapSubject: Obs[Obs[(K, D, V, String]]
                                                         |
             +-------------------------------------------+
             |  putOpinion/removeOpinion   +---------------+
             +---------------------------->| filterOpinion |--+
                                           +---------------+  |
             +------------------------------------------------+
             |     update due to an insertion
             +---+-------------------------------+--> (K, V) updates
                 |                               |
                 | update due to a removal       |
                 +---> opinionRemovalSubject ----+
     */
    private val updatesObservable =
        Observable.merge(
            Observable.merge[(K, D, V, String)](privateMapSubject)
                .filter(makeFunc1(filterOpinion))
                .map[(K, V)](makeFunc1(update => (update._1, update._3))),
            opinionRemovalSubject)

    /* Proxy is the observable clients of this class will subscribe to when
       calling the observable method below. Proxy subscribes to
       [[updateObservable]] as soon as the first owner joins this merged map
       to ensure that any insertion or removal of opinions will be handled in
       the filter function.*/
    private val proxy = PublishSubject.create[(K, V)]()

    /* This is used to determine when [[proxy]] needs to subscribe to
       [[updatesObservable]] when the first owner joins. Note that we never
       unsubscribe from [[updatesObservable]]. */
    private val subscribed = new AtomicBoolean(false)
    private val privateMaps = TrieMap[String, PrivateMapState[K, D, V]]()

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
        val roSnapshot = map.readOnlySnapshot
        val result = mutable.HashMap[K, V]()

        for ((key, opinions) <- roSnapshot) {
            val winning = winner(opinions)
            if (winning != null) {
                result(key) = winning
            }
        }
        result.toMap
    }

    /**
     * @return The winning opinion associated to this key.
     */
    override def get(key: K): V = map.get(key) match {
        case Some(opinions) =>
             winner(opinions)

        case _ => null.asInstanceOf[V]
    }

    /* This method is called when an observer subscribes to the observable
       below. */
    override def call(s: Subscriber[_ >: (K, V)]): Unit = {
        for (entry <- snapshot) {
            s.onNext(entry)
        }
        proxy subscribe s
    }

    /**
     * @return An observable that emits the content of this map upon subscription
     *         followed by updates to this map. The underlying conflict
     *         resolution strategy is used to determine the winning opinion when
     *         more than one value exists for a given key in the
     *         various private maps. A null value indicates that the corresponding
     *         key has been removed.
     */
    override def observable: Observable[(K, V)] = Observable.create(this)

    /**
     * Takes actions when one of the private maps put/removes an opinion and
     * returns true iff the update should pass through.
     *
     * Note that in the case of a removal, an update will possibly be emitted
     * on [[opinionRemovalSubject]]. The [[updatesObservable]] is never notified
     * in that case.
     */
    private def filterOpinion(opinion: (K, D, V, String)): Boolean = {
        opinion match {
            case (key, null, null, ownerId) =>
                removeOpinionsFromOwner(ownerId, key)

            case (key, discriminant, value, ownerId) =>
                newOpinionFromOwner(key, discriminant, value, ownerId)

            case _ =>
                log.warn("Unrecognized update {}", opinion)
                false
        }
    }

    /**
     * Removes opinions of the given owner and key and returns
     * false since the update is never passed to [[updatesObservable]].
     */
    private def removeOpinionsFromOwner(ownerId: String, key: K)
    : Boolean = this.synchronized {
        map.get(key) match {
            case Some(opinions) =>
                val (prevD, prevV) = winningPair(opinions)

                // The size method called by another thread can temporarily
                // give a result that is off by one (before we decrement
                // entryCount). This should be tolerated.
                opinions.retain((d: D, v: (V, String)) => !v._2.equals(ownerId))
                if (opinions.isEmpty && prevV != null) {
                    entryCount.decrementAndGet()
                }

                val (curD, curV) = winningPair(opinions)

                // The winning opinion for the key has changed, emit the new
                // winning opinion.
                if (!(prevD, prevV).equals((curD, curV))) {
                    opinionRemovalSubject.onNext((key, curV))
                }

            case _ =>
        }

        // The new opinion has already been emitted on 'removalSubject'.
        false
    }

    /**
     * Handles the insertion of a new opinion by the given owner and returns
     * true iff the update should pass through to [[updatesObservable]].
     */
    private def newOpinionFromOwner(key: K, discriminant: D, value: V,
                                    ownerId: String): Boolean =
        this.synchronized {
            // The size method called by another thread can temporarily give a
            // result that is off by one (before we increment entryCount).
            // This should be tolerated.
            map.putIfAbsent(key, TrieMap[D, (V, String)]()) match {
                case Some(prevOpinions) =>
                    if (prevOpinions.isEmpty) {
                        entryCount.incrementAndGet()
                    }
                case None => entryCount.incrementAndGet()
            }
            // We never remove entries from variable map, and thus opinions
            // cannot be null.
            val opinions = map(key)
            opinions.put(discriminant, (value, ownerId))

            // If this is a winning opinion, then we do not filter the update.
            (winningPair(opinions) == (discriminant, value))
    }

    /**
     * Returns a new private map for the given owner. The returned private map
     * will be merged with this merged map. If the given owner already joined
     * this map, then we return the previously created private map.
     */
    override def join(ownerId: String): PrivateMap[K, D, V] = {
        owners.get(ownerId) match {
            case Some(privateMap) => privateMap
            case _ =>
                if (subscribed.compareAndSet(false, true)) {
                    updatesObservable subscribe proxy
                }
                val privateMap = new InMemoryPrivateMap[K, D, V](ownerId)
                val privateMapState = new PrivateMapState[K, D, V](privateMap)
                owners.put(ownerId, privateMap)
                privateMaps.put(ownerId, privateMapState)
                privateMapSubject onNext privateMapState.observable
                privateMap
        }
    }

    /**
     * Removes the private map of the given owner from this merged map.
     */
    override def leave(ownerId: String): Unit = {
        owners.remove(ownerId) match {
            case Some(privateMap) =>
                privateMaps.remove(ownerId) match {
                    case Some(privateMapState) => privateMapState.complete()
                    case _ =>
                }
                for (key <- map.keySet) {
                    removeOpinionsFromOwner(ownerId, key)
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
        val roSnapshot = map.readOnlySnapshot
        val keys = mutable.MutableList[(D, K)]()

        // Collect the keys associated to the given value along with their
        // discriminant. Filter out values that are not winning opinions.
        for (key <- roSnapshot.keySet) {
            roSnapshot.get(key) match {
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
}

/**
 * An in-memory implementation of the PrivateMap trait. It is assumed that
 * a single-thread will call methods of this class.
 */
private[storage] class InMemoryPrivateMap[K, D <: Any, V <: AnyRef]
    (ownerId: String) extends PrivateMap[K, D, V] {

    private val map = mutable.HashMap[K, (D, V)]()
    private val subject = PublishSubject.create[(K, D, V, String)]()

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
     * @return An observable that emits updates to this map. A null value for
     *         a given key is emitted to indicate that the key has been removed.
     */
    override def observable: Observable[(K, D, V, String)] = subject

    /**
     * @return The number of entries in this map.
     */
    override def size: Int = map.size

    /**
     * Associates the given non-null opinion to this key. The value must be
     * immutable.
     *
     * @return The previous opinion associated to this key and in this private
     *         map, or null if no such opinion exists.
     */
    override def putOpinion(key: K, discriminant: D, value: V): V = {
        val prev = map.put(key, (discriminant, value))
        subject.onNext((key, discriminant, value, ownerId))
        prev match {
            case Some((d, v)) => v
            case None => null.asInstanceOf[V]
        }
    }

    /**
     * Removes the opinion associated with this key, if any.
     * @return The opinion previously put in this private map and associated to
     *         this key, or null if no such opinion exists.
     */
    override def removeOpinion(key: K): V = {
        val prev = map.remove(key)
        prev match {
            case Some((d, v)) =>
                subject.onNext(
                    (key, null.asInstanceOf[D], null.asInstanceOf[V], ownerId)
                )
                v
            case None => null.asInstanceOf[V]
        }
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