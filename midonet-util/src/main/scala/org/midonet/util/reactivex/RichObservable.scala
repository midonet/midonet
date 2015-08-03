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

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise, Future}
import scala.util.{Success, Failure, Try}

import rx.{Observable, Subscriber}

object RichObservable {
    val CompletedException = new IllegalStateException("Observable completed")
}

class RichObservable[T](observable: Observable[T]) {

    def asFuture: Future[T] = {
        val promise = Promise[T]()
        @volatile var completed = false
        observable.subscribe(new Subscriber[T]() {
            override def onCompleted(): Unit = {
                if (completed) return
                completed = true
                promise.failure(RichObservable.CompletedException)
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

    @throws[Throwable]
    def await(timeout: Duration = Duration.Inf): T = {
        Await.result(asFuture, timeout)
    }

    /**
     * Applies the given partial function to the next notification emitted by
     * the underlying observable.
     */
    def andThen[U](pf: PartialFunction[Try[T], U]): Observable[T] = {
        @volatile var completed = false
        observable.subscribe(new Subscriber[T]() {
            override def onCompleted(): Unit = {
                if (completed) return
                completed = true
                pf apply Failure(RichObservable.CompletedException)
            }

            override def onError(e: Throwable): Unit = {
                if (completed) return
                completed = true
                pf apply Failure(e)
            }

            override def onNext(t: T): Unit = {
                if (completed) return
                completed = true
                pf apply Success(t)
                unsubscribe()
            }
        })
        observable
    }

}
