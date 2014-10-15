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

import rx.{Observer, Observable, Subscription}
import rx.subjects.{PublishSubject, Subject}

/**
 * A Funnel is an aggregation component that groups a bunch of individual
 * subscriptions. It exposes a protected API to manipulate the aggregation,
 * adding or removing subscriptions transparently to subscribers.
 *
 * TODO: clarify threading, but we think this is thread safe as it's confined
 * to each Netty channel's thread.
 * @param KEY is the observable Id type (the key to distinguish the 'source'
 *          observables)
 * @param TYPE is the type of the elements emitted by the observables
 */
class Funnel[KEY,TYPE] {

    /* The channel where all subscriptions get put */
    private val funnel: Subject[TYPE, TYPE] = PublishSubject.create()

    /* The index of subscriptions for each ObservableId */
    private val sources = new ConcurrentHashMap[KEY, Subscription]

    /* The funnel has been disposed of (sync by 'sources') */
    private var done = false

    /** Subscribe to the funnel */
    def observable(): Observable[TYPE] = funnel.asObservable()

    /** Exclude updates from the given entity. */
    def drop(what: KEY): Unit = {
        val sub = sources.remove(what)
        if (sub != null) {
            sub.unsubscribe()
        }
    }

    /** Add the given Observable into the Funnel. */
    def add(what: KEY, o: Observable[_ <: TYPE]): Unit = sources.synchronized {
        if (!done && !sources.containsKey(what)) {
            sources.put(what, o subscribe funnel)
        }
    }

    /** Use when there is no need to keep this Funnel around anymore, triggers
      * the completion of the output funnel, and releases all the underlying
      * subscriptions. */
    def dispose(): Unit = sources.synchronized {
        done = true
        sources values() foreach { _.unsubscribe() }
        funnel.onCompleted()
    }

    /** Allows injecting a single message into the outbound funnel,
      * subscribing, as long as the Funnel is not released by the time that the
      * data is received. */
    def inject(m: TYPE): Unit = {
        if (!done)
            funnel onNext m
    }
}
