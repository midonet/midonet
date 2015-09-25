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

import java.util.concurrent.Executors.newSingleThreadExecutor
import javax.annotation.Nonnull

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import com.lmax.disruptor.util.DaemonThreadFactory
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.observers.SerializedObserver
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber}

import org.midonet.util.functors.{makeAction0, makeAction1, makeRunnable}

/**
 * This trait exposes a bus that is to be used with a merged map.
 * Among others, this trait exposes an observable that emits opinions of the
 * form (key, value, owner) coming from participants of this merged map, and
 * an observer to which opinions coming from this merged map are emitted.
 * An opinion with a null value indicates that the opinion is deleted.
 */
trait MergedMapBus[K, V] {

    /**
     * An opinion is a (key, value, owner) triple
     */
    type Opinion = (K, V, String)

    /**
     * @return The map id this bus corresponds to.
     */
    def mapId: String

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

    /**
     * Completes the opinionObservable.
     */
    def close(): Unit
}

object MergedMap {
    case class MapUpdate[K, V >: Null <: AnyRef](key: K, oldValue: V, newValue: V)
    private[storage] val executor =
        newSingleThreadExecutor(DaemonThreadFactory.INSTANCE)
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
 *
 * This class is thread-safe. Public methods can be called from multiple threads
 * concurrently. The subscription to the opinion bus observable
 * as well as handling its notifications are performed on the executor
 * defined in the companion object.
 */
class MergedMap[K, V >: Null <: AnyRef](opinionBus: MergedMapBus[K, V])
                                       (implicit crStrategy: Ordering[V]) {

    import MergedMap._

    type MapUpdate = MergedMap.MapUpdate[K, V]

    private val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.cluster.state.MergedMap-${opinionBus.mapId}"))

    /* The cache is always modified from within [[myScheduler]] and therefore
       does not need to be thread-safe. */
    private val cache = new mutable.HashMap[K, mutable.HashMap[String, V]]()
    /* A map storing the winning opinion for each key. */
    private val winners = new TrieMap[K, V]()

    /* The scheduler on which subscriptions to [[winnersObservable]] and opinion
       addition/removal are scheduled. */
    private val myScheduler = Schedulers.from(executor)

    private val ownerId = opinionBus.owner

    /* A thread-safe opinion observer */
    private val safeObserver =
        new SerializedObserver[(K, V, String)](opinionBus.opinionObserver)

    /* The subject on which winning opinions are emitted. */
    private val winnersSubject = PublishSubject.create[MapUpdate]()

    /* A snapshot of the winners map as an iterable of MapUpdates. For each
       MapUpdate, the previous value is null. */
    private def winnersIterable: Iterable[MapUpdate] =
        winners.toIterable.map(entry => MapUpdate(entry._1, null, entry._2))

    /* This is called when an observer subscribes to [[winningObservable]]. */
    private val onSubscribe = new OnSubscribe[MapUpdate] {
        override def call(s: Subscriber[_ >: MapUpdate]): Unit = {
            winnersSubject.startWith(Observable.from(winnersIterable.asJava))
                          .subscribe(s)
        }
    }
    /* The observable passed to the outside world. Whenever an observer
       subscribes to it, we first emit the winning opinions in the map, followed
       by updates coming from [[winningSubject]]. */
    private val winnersObservable = Observable.create(onSubscribe)
                                              .subscribeOn(myScheduler)

    opinionBus.opinionObservable.observeOn(myScheduler)
                                .subscribe(makeAction1(onNewOpinion),
                                           makeAction1(onError),
                                           makeAction0(onCompleted()))

    private def onError(e: Throwable): Unit = {
        log.warn("Can't receive updates for map: " + opinionBus.mapId, e)
        winnersSubject.onCompleted()
    }

    private def onCompleted(): Unit = {
        log.debug("Stream of updates for map: " + opinionBus.mapId + " ended")
        winnersSubject.onCompleted()
    }

    /* This method handles opinions emitted from the opinion bus. */
    private def onNewOpinion(opinion: (K, V, String)): Unit = opinion match {
        case (null, _, owner) =>
            throw new IllegalStateException("Null key on the opinion bus " +
                                            "from owner: " + owner)
        case (_, value, null) =>
            throw new IllegalStateException("Null owner on the opinion bus " +
                                            "for value: " + value)
        // Signals the the given opinion is being deleted.
        case (key, null, owner) =>
            onOpinionRemoval(key, owner)
        case (key, value, owner) =>
            onOpinionAddition(key, value, owner)
    }

    private def winner(opinions: mutable.HashMap[String, V]): V = {
        if (opinions.isEmpty) {
            null
        } else {
            opinions.maxBy[V](_._2)._2
        }
    }

    private def onOpinionAddition(key: K, value: V, owner: String): Unit = {
        val opinions = cache.getOrElseUpdate(key, new mutable.HashMap[String, V])
        opinions += owner -> value
        val prevWinner = winners.get(key)

        // If this is the 1st winning opinion for this key or the added value
        // is greater than the previous winner, emit the new winner.
        if (prevWinner.isEmpty || crStrategy.gt(value, prevWinner.get)) {
            winners.put(key, value)
            winnersSubject onNext MapUpdate(key, prevWinner.getOrElse(null), value)
        }
    }

    private def onOpinionRemoval(key: K, owner: String): Unit = {
        cache.get(key).foreach(opinions => {
            val prevWinner = winners.get(key)
            opinions -= owner
            val newWinner = winner(opinions)

            // The winning opinion for the key has changed, update the
            // winners maps and emit the new winning opinion.
            if (prevWinner.nonEmpty && !prevWinner.get.equals(newWinner)) {
                if (newWinner != null) {
                    winners.put(key, newWinner)
                } else {
                    winners.remove(key)
                }
                winnersSubject onNext MapUpdate(key, prevWinner.get, newWinner)
            }
        })
    }

    /**
     * @return An observable that emits the winning entries of this map
     *         upon subscription followed by winning updates made to this
     *         map. Emitted updates are of the form (key, oldValue, newValue).
     *         When newValue is null this indicates that the corresponding key
     *         has been removed.
     */
    def observable: Observable[MapUpdate] = winnersObservable

    /**
     * @return The winning opinion associated to this key.
     */
    def get(key: K): V = winners.get(key).orNull

    /**
     * @return The number of entries in this map.
     */
    def size = winners.size

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
    def getByValue(value: V): Iterable[K] =
        winners.readOnlySnapshot.filter(entry => entry._2.equals(value)).keys

    /**
     * Associates the given non-null opinion to this non-null key.
     */
    def putOpinion(@Nonnull key: K, @Nonnull value: V): Unit =
        safeObserver onNext (key, value, ownerId)

    /**
     * Removes the opinion associated with this non-null key, if any.
     */
    def removeOpinion(@Nonnull key: K): Unit =
        safeObserver onNext (key, null, ownerId)

    /**
     * Closes the opinion bus (which completes the map's observable), and clears
     * the map's locally cached content.
     */
    def close(): Unit = {
        // This will complete the winnersObservable.
        opinionBus.close()
        executor.submit(makeRunnable(cache.clear()))
        winners.clear()
    }
}
