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
 * @param I is the observable Id type (the key to distinguish the 'source'
 *          observables)
 * @param T is the type of the elements emitted by the observables
 */
class Funnel[I,T] {

    /* The channel where all subscriptions get put */
    private val funnel: Subject[T, T] = PublishSubject.create()

    /* The index of subscriptions for each ObservableId */
    private val sources = new ConcurrentHashMap[I, Subscription]

    /** Subscribe to the funnel */
    def observable(): Observable[T] = funnel.asObservable()

    /** Exclude updates from the given entity. */
    def drop(what: I): Unit = {
        val sub = sources.remove(what)
        if (sub != null) {
            sub.unsubscribe()
        }
    }

    /** Add the given Observable into the Funnel. */
    def add(what: I, o: Observable[_ <: T]): Unit = {
        // TODO: we must sync this, unfortunately.
        if (!sources.containsKey(what)) {
            sources.put(what, o subscribe funnel)
        }
    }

    /* This is here because ZOOM requires passing an Observer instead of
     * exposing the Observable (with a good reason, this allows maintaining
     * completeness guarantees on the stream, but it's annoying as happens
     * here. This method allows the caller passing a function that will forward
     * our private subscriber to the right Observable (that is, to ZOOM), and
     * give us the Subscription back. The alternative to this would be exposing
     * our private funnel, which we would rather avoid, or moving the storage
     * into the Funnel, which is not too nice either. */
    protected[topology] def add(what: I,
                                f: (Observer[_ >: T]) => Subscription)
    : Unit = {
        // TODO: we must sync this, unfortunately.
        if (!sources.containsKey(what)) {
            sources.put(what, f(funnel))
        }
    }

    /** Use when there is no need to keep this Funnel around anymore, triggers
      * the completion of the output funnel, and releases all the underlying
      * subscriptions. */
    def dispose(): Unit = {
        sources values() foreach { _.unsubscribe() }
        funnel.onCompleted()
    }

    /** Allows injecting a single message into the outbound funnel,
      * subscribing, as long as the Funnel is not released by the time that the
      * data is received. */
    def inject(m: T): Unit = {
        funnel onNext m
    }
}
