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
package org.midonet.util

import scala.concurrent.{Promise, Future}

import rx.{Observer, Subscriber, Observable}

package object reactivex {

    implicit def richObservable[T](observable: Observable[T]) = new {

        def asFuture: Future[T] = {
            val promise = Promise[T]()
            var completed = false
            observable.subscribe(new Subscriber[T]() {
                override def onCompleted(): Unit = {
                    if (completed) return
                    completed = true
                    promise.failure(
                        new IllegalStateException("Observable completed"))
                }

                override def onError(e: Throwable): Unit = {
                    if (completed) return
                    completed = true
                    promise.failure(e)
                }

                override def onNext(value: T): Unit = {
                    if (completed) return
                    completed = true
                    promise.success(value)
                    unsubscribe()
                }
            })
            promise.future
        }
    }

    implicit def richObserver[T](observer: Observer[T]) = new {

        def asSubscriber: Subscriber[T] = new Subscriber[T]() {
            override def onCompleted(): Unit = observer.onCompleted()

            override def onError(e: Throwable): Unit = observer.onError(e)

            override def onNext(t: T): Unit = observer.onNext(t)
        }
    }

}
