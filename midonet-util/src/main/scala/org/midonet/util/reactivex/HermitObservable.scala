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

import rx.Observable.OnSubscribe
import rx.functions.Action0
import rx.internal.operators.OperatorDoOnUnsubscribe
import rx.{Observable, Subscriber, Subscription}
import org.midonet.util.functors.makeAction0

/** Turns an Observable into a hermit, only accepting a single subscription at
  * any given point in time.
  *
  * TODO: thread safe this. */
object HermitObservable {

    implicit def hermitize[T](o: Observable[T]): Observable[T]
        = Observable.create(new OnSubscribeHermit(o))

    class OnSubscribeHermit[T](o: Observable[T]) extends OnSubscribe[T] {

        /** A PrivateObservable rejects a subscription, for it has one already */
        class HermitException
            extends RuntimeException("Observable already has a subscription")

        /* The subscription of our current owner */
        private var currentSubscription: Option[Subscription] = None

        @inline
        private final def hasOwner = !(currentSubscription.isEmpty ||
                                       currentSubscription.get.isUnsubscribed)

        override def call(subscriber: Subscriber[_ >: T]): Unit = {
            if (hasOwner) {
                throw new HermitException
            }
            currentSubscription = Some(
                o.doOnUnsubscribe(makeAction0 {currentSubscription = None})
                 .subscribe(subscriber)
            )
        }
    }
}
