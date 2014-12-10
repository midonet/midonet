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

package org.midonet.util.reactivex

import java.util.concurrent.atomic.AtomicBoolean

import rx.Observable.OnSubscribe
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber, Subscription}

import org.midonet.util.functors.makeAction0

/** Turns an Observable into a hermit, only accepting a single subscription at
  * any given point in time. */
object HermitObservable {

    /** Rejects a subscription, for it has one already. */
    class HermitOversubscribedException
        extends RuntimeException("Observable already has a subscription")

    implicit def hermitize[T](o: Observable[T]): Observable[T]
        = Observable.create(new OnSubscribeHermit(o))

    /** Turns a given Observable into a hermit that will only accept a single
      * subscriber at any point in time.
      *
      * @param o the Observable to turn into a hermit
      * @throws HermitOversubscribedException if a subscriber tries to subscribe
      *                                       while another subscriber is
      *                                       already subscribed
      */
    class OnSubscribeHermit[T](private val o: Observable[T])
        extends OnSubscribe[T] {

        private val owned = new AtomicBoolean(false)

        override def call(subscriber: Subscriber[_ >: T]): Unit = {
            if (!owned.compareAndSet(false, true)) {
                throw new HermitOversubscribedException
            }
            // we're the sole owners of this observable now, we can subscribe
            o.subscribe(subscriber)
            subscriber.add (
                Subscriptions.create( makeAction0 { owned set false } )
            )
        }
    }
}
