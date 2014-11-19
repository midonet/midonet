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
package org.midonet.util.reactivex.observables

import rx.Observable

import org.midonet.util.reactivex.operators.OnSubscribeFunnel

object FunnelObservable {

    /**
     * Creates an [[rx.Observable]] that combines notification from a sequence
     * of source observables, specified as an observable of observables.
     * Whenever the sequence of observables emits a new source observable,
     * all current subscribers of the [[FunnelObservable]] are automatically
     * subscribed to it.
     *
     * Subscribers are un-subscribed from an individual source observables
     * when the source observable completes. Subscribers are un-subscribed from
     * all source observables in the funnel and the funnel itself, when calling
     * the subscription unsubscribe() method.
     *
     *
     * The [[FunnelObservable]] does not limit the number of source observables,
     * and provides an unbounded back-pressure cache for each subscriber. For
     * this reason, it is not recommended to use the [[FunnelObservable]] with
     * subscribers that consume events at a rate lower than the emission rate.
     *
     * The [[FunnelObservable]] does not emit onCompleted notification from any
     * of the source observables. However, it does emit an onCompleted
     * notification when the sequence of source observables completes, and an
     * onError notification when either the sequence of observable or any of
     * the source observables emits an error.
     */
    def create[T](observables: Observable[Observable[T]]): Observable[T] = {
        new FunnelObservable[T](observables)
    }
}

final class FunnelObservable[T](observables: Observable[Observable[T]])
    extends Observable[T](new OnSubscribeFunnel[T](observables)) {
}