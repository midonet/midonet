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

import scala.concurrent.{Future, Promise}

import rx.{Observable, Subscriber}

object RichObservable {
    val COMPLETED_EXCEPTION = new IllegalStateException("Observable completed")
}

/**
 * A class defining additional utility methods for an [[rx.Observable]].
 */
class RichObservable[T](val observable: Observable[T]) extends AnyVal {

    def asFuture: Future[T] = {
        val promise = Promise[T]()
        var completed = false
        observable.subscribe(new Subscriber[T]() {
            override def onCompleted(): Unit = {
                if (completed) return
                completed = true
                promise.failure(RichObservable.COMPLETED_EXCEPTION)
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
