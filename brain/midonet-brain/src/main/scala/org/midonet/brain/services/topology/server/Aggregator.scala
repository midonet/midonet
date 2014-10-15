/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.services.topology.server

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConversions._

import org.slf4j.LoggerFactory

import rx.functions.{Action1, Func1, Action0}
import rx.Observable
import rx.observables.ConnectableObservable
import rx.subjects.{PublishSubject, Subject}

/**
 * An Aggregator is an aggregation component that groups a bunch of individual
 * observables. It exposes a protected API to manipulate the aggregation,
 * adding or removing observables transparently to subscribers.
 * NOTE: data from observables added before a particular subscriber is
 * subscribed is ignored.
 *
 * TODO: clarify threading, but we think this is thread safe as it's confined
 * to each Netty channel's thread.
 * @param KEY is the observable Id type (the key to distinguish the 'source'
 *          observables)
 * @param TYPE is the type of the elements emitted by the observables
 */
class Aggregator[KEY,TYPE] {
    private val log = LoggerFactory.getLogger(classOf[Aggregator[KEY, TYPE]])

    /* The collector channel where all observables appear */
    private val collector: Subject[Observable[TYPE], Observable[TYPE]] =
        PublishSubject.create()

    /* The flattened output stream */
    private val stream: Observable[TYPE] = Observable.merge(collector).share()

    /* The index of subscriptions for each Observable key */
    type Terminator = ConnectableObservable[Null]
    private val sources = new ConcurrentHashMap[KEY, Terminator]()

    /* Indicate that the collector has been disposed of */
    /* Note: changes are protected via 'sources' synchronization */
    private var done = false

    /** subscribe to the funnel */
    def observable(): Observable[TYPE] = stream

    /** Add the given Observable into the Aggregator */
    def add(what: KEY, o: Observable[_ <: TYPE]): Unit = sources.synchronized
    {
        lazy val terminator: Terminator = Observable.just(null).publish()
        lazy val src = o.asInstanceOf[Observable[TYPE]].takeUntil(terminator)
            .onErrorResumeNext(new Func1[Throwable, Observable[TYPE]] {
                override def call(err: Throwable): Observable[TYPE] = {
                    log.error("error in aggregated observable: " + what, err)
                    Observable.empty()
                }
            })
            .doOnCompleted(new Action0 {
                override def call(): Unit = {
                    drop(what)
                }
            })
        if (!done && !sources.containsKey(what)) {
            sources.put(what, terminator)
            collector.onNext(src)
        }
    }

    /** Remove observables from the Aggregator */
    def drop(what: KEY): Unit = {
        val terminator = sources.remove(what)
        if (terminator != null) {
            terminator.connect()
        }
    }

    /** Use when there is no need to keep this Aggregator around anymore,
      * completing the collected observables (implicitly releasing the
      * underlying subscriptions) and triggering  the completion of the
      * output funnel */
    def dispose(): Unit = sources.synchronized {
        done = true
        sources.values().foreach { _.connect() }
        collector.onCompleted()
    }

    /** Allows injecting a single message into the outbound funnel,
      * subscribing, as long as the Aggregator is not released by the time that the
      * data is received. */
    def inject(m: TYPE): Unit = {
        if (!done)
            collector.onNext(Observable.just(m))
    }
}
