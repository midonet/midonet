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

package org.midonet.util.reactivex

import rx.{Observable, Observer}

object RequestObserver {
    def apply[T](obs: Observable[T]) =
        new RequestObserver[T](obs) with AwaitableObserver[T]
}

class RequestObserver[T](obs: Observable[T]) extends Observer[T] {
    obs.subscribe(this)
    override def onCompleted(): Unit = {}
    override def onError(t: Throwable): Unit = {}
    override def onNext(t: T): Unit = {}
}