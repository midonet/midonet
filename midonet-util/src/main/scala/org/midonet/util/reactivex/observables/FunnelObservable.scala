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

    def create[T](observables: Observable[_ <: T]*): FunnelObservable[T] = {
        new FunnelObservable[T](new OnSubscribeFunnel[T](observables))
    }

}

/**
 * An [[rx.Observable]] which combines notifications from a list of source
 * observables. The list of sources can be modified by adding and removing
 * observables with the add() and remove() methods.
 *
 * When adding a source observable, all current subscribers will subscribe
 * to the observable. When removing a source observable, all current subscribers
 * will unsubscribe from the source observables.
 *
 * The [[FunnelObservable]] does not limit the number of source observables, and
 * provides an unbounded back-pressure cache for each subscriber. For this
 * reason, it is not recommended to use the [[FunnelObservable]] with
 * subscribers that consume events at a rate lower than the emission rate.
 *
 * The [[FunnelObservable]] never issues an onCompleted notification. When
 * a source observable completes, all subscribers are silently unsubscribed
 * from that observable.
 */
final class FunnelObservable[T] protected (f: OnSubscribeFunnel[T])
        extends Observable[T](f) {

    def add(obs: Observable[_ <: T]): Unit = {
        f.add(obs)
    }

    def remove(obs: Observable[_ <: T]): Unit = {
        f.remove(obs)
    }

}

