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

import java.util.concurrent.CountDownLatch

import scala.concurrent.duration.Duration

import rx.observers.TestObserver

class AwaitableObserver[T](awaitCount: Int = 1, assert: => Unit = {})
    extends TestObserver[T] {

    def this(awaitCount: Int) = this(awaitCount, {})

    @volatile private var counter = new CountDownLatch(awaitCount)

    override def onNext(value: T): Unit = synchronized {
        super.onNext(value)
        assert
        counter.countDown()
    }

    override def onCompleted(): Unit = {
        super.onCompleted()
        assert
        counter.countDown()
    }

    override def onError(e: Throwable): Unit = {
        super.onError(e)
        assert
        counter.countDown()
    }

    def await(duration: Duration): Boolean = {
        counter.await(duration.length, duration.unit)
    }

    def await(duration: Duration, resetCount: Int): Boolean = try {
        counter.await(duration.length, duration.unit)
    } finally {
        counter = new CountDownLatch(resetCount)
    }

    def reset(resetCount: Int): Unit = {
        counter = new CountDownLatch(resetCount)
    }
}
