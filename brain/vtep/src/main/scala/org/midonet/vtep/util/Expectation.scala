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

package org.midonet.vtep.util

import java.util.concurrent.Executor

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Failure, Success}

/**
 * A class allowing to use scala promises and futures from Java,
 * @tparam T is the type of the promise value
 */
class Expectation[T] {
    import Expectation._
    val promise = Promise[T]()
    def success(value: T): Unit = promise.success(value)
    def failure(exception: Throwable): Unit = promise.failure(exception)
    def onSuccess(cb: OnSuccess[T], ec: ExecutionContext): Unit =
        promise.future.onSuccess({case v => cb.call(v)})(ec)
    def onSuccess(cb: OnSuccess[T], exec: Executor): Unit =
        promise.future.onSuccess(
        {case v => cb.call(v)})(ExecutionContext.fromExecutor(exec))
    def onFailure(cb: OnFailure, ec: ExecutionContext): Unit =
        promise.future.onFailure({case e => cb.call(e)})(ec)
    def onFailure(cb: OnFailure, exec: Executor): Unit =
        promise.future.onFailure(
        {case e => cb.call(e)})(ExecutionContext.fromExecutor(exec))
    def onFailureForward(exp: Expectation[_], ec: ExecutionContext): Unit =
        promise.future.onFailure({case e => exp.failure(e)})(ec)
    def onFailureForward(exp: Expectation[_], exec: Executor): Unit =
        promise.future.onFailure(
        {case e => exp.failure(e)})(ExecutionContext.fromExecutor(exec))
    def onComplete(cb: OnComplete[T], ec: ExecutionContext): Unit =
        promise.future.onComplete({
            case Success(v) => cb.onSuccess(v)
            case Failure(e) => cb.onFailure(e)
        })(ec)
    def onComplete(cb: OnComplete[T], exec: Executor): Unit =
        promise.future.onComplete({
            case Success(v) => cb.onSuccess(v)
            case Failure(e) => cb.onFailure(e)
        })(ExecutionContext.fromExecutor(exec))
    def future: Future[T] = promise.future
}

object Expectation {
    trait OnSuccess[T] {
        def call(value: T): Unit
    }
    trait OnFailure {
        def call(exception: Throwable): Unit
    }
    trait OnComplete[T] {
        def onSuccess(value: T): Unit = {}
        def onFailure(exception: Throwable): Unit = {}
    }
}
