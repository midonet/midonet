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

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import rx.subscriptions.Subscriptions
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.util.functors._

class FutureOps[T](val f: Future[T]) extends AnyVal {

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

    /**
     * Creates an [[Observable]] from this future which for every subscriber:
     * (a) if the future completes successfully will emit the future value
     * and complete the observable, or (b) if the future fails will emit the
     * future error. If the future is already completed at the moment of the
     * subscription, the notifications are immediate on the caller thread.
     * Otherwise the notifications are received on the thread of the given
     * [[ExecutionContext]].
     */
    def asObservable(implicit executor: ExecutionContext): Observable[T] = {
        val subscribers = new mutable.HashSet[Subscriber[_ >: T]]
        f.onComplete {
            case Success(t) => subscribers.synchronized {
                for (child <- subscribers) {
                    child.onNext(t)
                    child.onCompleted()
                }
            }
            case Failure(e) => subscribers.synchronized {
                for (child <- subscribers) {
                    child.onError(e)
                }
            }
        }
        Observable.create(new OnSubscribe[T] {
            override def call(child: Subscriber[_ >: T]): Unit = {
                subscribers.synchronized {
                    f.value match {
                        case Some(Success(t)) =>
                            child.onNext(t)
                            child.onCompleted()
                        case Some(Failure(e)) =>
                            child.onError(e)
                        case None =>
                            subscribers add child
                            child.add(Subscriptions.create(makeAction0 {
                                subscribers.synchronized {
                                    subscribers.remove(child)
                                }
                            }))
                    }
                }
            }
        })
    }

    def await(timeout: Duration = 1 second): T = {
        Await.result(f, timeout)
    }
}
