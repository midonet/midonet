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

package org.midonet.cluster.services.topology.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConversions._

import org.slf4j.LoggerFactory

import rx.Observable
import rx.observables.ConnectableObservable
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.topologyApiAggregatorLog
import org.midonet.util.functors.{makeAction0, makeFunc1}

/**
 * An Aggregator is an aggregation component that groups a bunch of individual
 * observables. It exposes a protected API to manipulate the aggregation,
 * adding or removing observables transparently to subscribers.
 * The completion and errors in the individual observables are not propagated
 * to the aggregated observable; therefore, any completion/error notifications
 * should be converted into an 'onNext' event in order to reach the final
 * subscriber.
 * NOTE: data from observables added before a particular subscriber is
 * subscribed is ignored.
 *
 * @tparam KEY is the observable Id type (the key to distinguish the 'source'
 *            observables)
 * @tparam TYPE is the type of the elements emitted by the observables
 */
class Aggregator[KEY,TYPE] {
    private val log = LoggerFactory.getLogger(topologyApiAggregatorLog)

    /* The collector channel where all observables appear */
    private val collector: Subject[Observable[TYPE], Observable[TYPE]] =
        PublishSubject.create()

    /* The flattened output stream */
    private val stream: Observable[TYPE] =
        Observable.merge(collector).serialize()

    /* The index of subscriptions for each Observable key */
    type Terminator = ConnectableObservable[Null]
    case class SourceControl(terminator: Terminator, owner: UUID)
    private val sources = new ConcurrentHashMap[KEY, SourceControl]()

    /* Indicate that the collector has been disposed of */
    private val disposed = new AtomicBoolean(false)

    /** subscribe to the funnel */
    def observable(): Observable[TYPE] = stream

    /** Add the given Observable into the Aggregator; it returns the owner
      * of the entry, if it already exists, or the new owner, if successfully
      * set; completion and error events are not propagated to the aggregated
      * observable */
    def add(key: KEY, o: Observable[_ <: TYPE], owner: UUID): UUID = {
        if (disposed.get())
            throw new IllegalStateException(
                "observable aggregator already disposed")

        val terminator: Terminator = Observable.just(null).publish()
        val controller = SourceControl(terminator, owner)
        val previous = sources.putIfAbsent(key, controller)
        if (previous == null) {
            val src = o.asInstanceOf[Observable[TYPE]].takeUntil(terminator)
                .onErrorResumeNext(makeFunc1[Throwable, Observable[TYPE]](err => {
                log.error("error in aggregated observable: " + key, err)
                Observable.empty()
            })).doOnCompleted(makeAction0({sources.remove(key, controller)}))
            collector.onNext(src)
            // rollback on dispose (make sure the observable is removed)
            if (disposed.get()) {
                // This connect ensures that the observable is completed
                terminator.connect()
            }
            owner
        } else {
            previous.owner
        }
    }

    /** Remove observables from the Aggregator */
    def drop(what: KEY): Unit = {
        val controller = sources.remove(what)
        if (controller != null) {
            controller.terminator.connect()
        }
    }

    /** Use when there is no need to keep this Aggregator around anymore,
      * completing the collected observables (implicitly releasing the
      * underlying subscriptions) and triggering  the completion of the
      * output funnel */
    def dispose(): Unit = {
        if (disposed.compareAndSet(false, true)) {
            // Note that a sources.clear() is not needed, since
            // connecting the terminator triggers the observable's
            // onComplete action, that removes its entry from sources
            sources.values() foreach {_.terminator.connect()}
            collector.onCompleted()
        }
    }

    /** Allows injecting a single message into the outbound funnel,
      * as long as the Aggregator is not disposed */
    def inject(m: TYPE): Unit = {
        if (!disposed.get())
            collector.onNext(Observable.just(m))
    }
}
