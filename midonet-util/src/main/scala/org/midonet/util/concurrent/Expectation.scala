/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.util.concurrent

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
 * A class to wrap scala promises and futures for using them from Java
 * @tparam T is the type of the promise value
 */
class Expectation[T](final val promise: Promise[T]) {
    def this() = this(Promise[T]())

    import Expectation._

    /** Set the success value */
    def success(value: T): Unit = promise.success(value)
    /** Set the failure exception */
    def failure(exception: Throwable): Unit = promise.failure(exception)


    /** Callbacks for promise completion */
    def onSuccess(cb: OnSuccess[T], ec: ExecutionContext): Unit =
        promise.future.onSuccess({case v => cb.call(v)})(ec)
    def onFailure(cb: OnFailure, ec: ExecutionContext): Unit =
        promise.future.onFailure({case e => cb.call(e)})(ec)
    def onComplete(cb: OnComplete[T], ec: ExecutionContext): Unit =
        promise.future.onComplete({
            case Success(v) => cb.onSuccess(v)
            case Failure(e) => cb.onFailure(e)
        })(ec)

    /** Extract the future */
    def future: Future[T] = promise.future

    /** Implicit conversion to future */
    implicit def expectationToFuture(e: Expectation[T]): Future[T] = e.future

    /** Wait until the promise has been fulfilled */
    @throws[TimeoutException]
    @throws[InterruptedException]
    def await(timeout: Duration): this.type = {
        Await.ready(promise.future, timeout)
        this
    }

    /* This is equivalent to using a default parameter, but usable from Java */
    @throws[TimeoutException]
    @throws[InterruptedException]
    def await(): this.type = {Await.ready(promise.future, Duration.Inf); this}

    /** Wait until the promise is fulfilled, and return the value or throw
      * the exception */
    @throws[Exception]
    def result(timeout: Duration): T = Await.result(promise.future, timeout)

    /* This is equivalent to using a default parameter, but usable from Java */
    @throws[Exception]
    def result(): T = Await.result(promise.future, Duration.Inf)
}

object Expectation {
    trait OnSuccess[T] {
        def call(value: T): Unit
    }
    trait OnFailure {
        def call(exception: Throwable): Unit
    }
    trait OnComplete[T] {
        def onSuccess(value: T): Unit
        def onFailure(exception: Throwable): Unit
    }
}
