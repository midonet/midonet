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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster.data.storage.MergedMap.MergedMapId
import org.midonet.util.functors.makeAction1

/**
 * This trait exposes a bus that is to be used with a merged map.
 * Among others, this trait exposes an observable that emits opinions of the
 * form (key, value, owner) coming from participants of this merged map, and
 * an observer to which opinions coming from this merged map are emitted.
 * An opinion with a null value indicates that the opinion is deleted.
 */
trait MergedMapBus[K, V] {
    /**
     * @return The map id this bus corresponds to.
     */
    def mapId: MergedMapId

    /**
     * @return The owner this bus is built for.
     */
    def owner: String

    /**
     * @return The observable emitting opinions of the form (key, value, owner)
     *         coming from the participants of this merged map.
     */
    def opinionObservable: Observable[(K, V, String)]
    /**
     * @return An observer to which opinions coming from this owner will be
     *         emitted and propagated to participants of this merged map.
     */
    def opinionObserver: Observer[(K, V, String)]
}

object MergedMap {
    object MergedMapType extends Enumeration {
        type MergedMapType = Value
        val ARP, MAC = Value
    }

    import MergedMapType._

    class MergedMapId(id: UUID, tableType: MergedMapType) {
        override def toString: String = id.toString + "/" + tableType
    }
    val executor = Executors.newSingleThreadExecutor()
}

/**
 * A merged map allows several values to be associated to the same key. These
 * values are called opinions and each opinion belongs to a specific owner.
 * The merged map relies on a conflict resolution strategy to only expose
 * winning opinions to the outside world. As such, the class V of map values
 * extends the Ordering trait.
 *
 * The opinionBus parameter below exposes an observable and an observer to
 * interact with the middleware used to propagate opinions to the map
 * participants. Opinions coming from merged maps are emitted from the opinion
 * bus observable. Opinions originating from this merged map that need to
 * be shared with participants will be emitted to the observer. Opinions
 * are of the form (key, value, owner). A null value indicates that the
 * corresponding opinion has been deleted.
 */
class MergedMap[K, V >: Null <: AnyRef](opinionBus: MergedMapBus[K, V])
                                       (implicit crStrategy: Ordering[V]) {

    import MergedMap._

    /* The cache is always modified from within [[myScheduler]] and therefore
       does not need to be thread-safe. */
    private val cache = new mutable.HashMap[K, mutable.HashMap[String, V]]()
    private val entryCount = new AtomicInteger(0)

    /* A map storing the winning opinion for each key. */
    private val winners = new TrieMap[K, V]()

    /* The scheduler on which subscriptions to [[updateStream]] and opinion
       addition/removal are scheduled. */
    private val myScheduler = Schedulers.from(executor)

    private val owner = opinionBus.owner

    /* The subject on which winning opinions are emitted. */
    private val winningSubject = PublishSubject.create[(K, V)]()

    /* This is called when someone subscribes to the observable returned
       by method observable (which is [[winningObservable]]). */
    private val onSubscribe = new OnSubscribe[(K, V)] {
        override def call(s: Subscriber[_ >: (K, V)]): Unit = {
            winningSubject.startWith(Observable.from(winners.readOnlySnapshot
                                                            .toIterable.asJava))
                          .subscribe(s)
        }
    }
    /* The observable passed to the outside world. Whenever someone subscribes
       to it, we first emit the winning opinions in the map, followed by
       updates coming from [[winningSubject]]. */
    private val winningObservable = Observable.create(onSubscribe)
                                              .subscribeOn(myScheduler)

    opinionBus.opinionObservable.observeOn(myScheduler)
                                .subscribe(makeAction1(onNewOpinion))

    /* This method handles opinions emitted from the opinion bus. */
    private def onNewOpinion(opinion: (K, V, String)): Unit = opinion match {
        // Signals the the given opinion is being deleted.
        case (key, null, owner) =>
            onOpinionRemoval(key, owner)
        case (key, value, owner) =>
            onOpinionAddition(key, value, owner)
    }
    
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
    def observable: Observable[(K, V)] = winningObservable

    /**
     * @return The winning opinion associated to this key.
     */
    def get(key: K): V = winners.get(key).orNull

    /**
     * @return The number of entries in this map.
     */
    def size = entryCount.get

    /**
     * @return True iff this cache contains the given key.
     */
    def containsKey(key: K): Boolean = winners.contains(key)

    /**
     * @return A snapshot of this map, where for each key, the conflict
     *         resolution strategy is used to determine the winning opinion.
     */
    def snapshot: Map[K, V] =  winners.readOnlySnapshot.toMap

    /**
     * @return All the keys associated to this value. This method only
     *         returns keys whose values are winning opinions.
     */
    def getByValue(value: V): List[K] =
        winners.filter(entry => entry._2.equals(value))
               .map(entry => entry._1)
               .toList

    /**
     * Associates the given non-null opinion to this key.
     */
    def putOpinion(key: K, value: V): Unit =
        opinionBus.opinionObserver onNext (key, value, owner)

    /**
     * Removes the opinion associated with this key, if any.
     */
    def removeOpinion(key: K): Unit =
        opinionBus.opinionObserver onNext (key, null, owner)
}
