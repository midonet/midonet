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
package org.midonet.util.concurrent


import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class FutureOps[+T](val f: Future[T]) extends AnyVal {

    /**
     * Continues the computation of this future by taking the current future
     * and mapping it into another future.
     *
     * The function `cont` is called only after the current future completes.
     * The resulting future contains a value returned by `cont`.
     */
    def continueWith[S](cont: Future[T] => S)
                       (implicit executor: ExecutionContext): Future[S] = {
        val p = Promise[S]()
        f.onComplete { _ =>
            p complete Try(cont(f))
        }(CallingThreadExecutionContext)
        p.future
    }

    /**
     *  Continues the computation of this future by taking the result
     *  of the current future and mapping it into another future.
     *
     *  The function `cont` is called only after the current future completes.
     *  The resulting future contains a value returned by `cont`.
     */
    def continue[S](cont: Try[T] => S)
                   (implicit executor: ExecutionContext): Future[S] = {
        val p = Promise[S]()
        f.onComplete { x =>
            p complete Try(cont(x))
        }(CallingThreadExecutionContext)
        p.future
    }

    /**
     * Returns a Future which will be completed with the result of `f`'s inner
     * future.
     */
    def unwrap[S](implicit ev: T <:< Future[S]): Future[S] = {
        val p = Promise[S]()
        f.onComplete {
            case Success(f2) =>
                f2.onComplete(p.complete)(CallingThreadExecutionContext)
            case Failure(t) =>
                p failure t
        }(CallingThreadExecutionContext)
        p.future
    }

    def await(timeout: Duration = 1 second): T = {
        Await.result(f, timeout)
    }
}
