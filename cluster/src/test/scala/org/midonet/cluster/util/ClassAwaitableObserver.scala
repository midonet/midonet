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

package org.midonet.cluster.util

import java.util.concurrent.CountDownLatch

import scala.collection.mutable
import scala.concurrent.duration.Duration

import rx.{Observable, Observer}

import org.midonet.util.reactivex.AwaitableObserver
import org.midonet.util.reactivex.AwaitableObserver.{Notification, OnCompleted, OnError}

class ClassAwaitableObserver[T](awaitCount: Int) extends Observer[Observable[T]] {

    val observers = new mutable.MutableList[AwaitableObserver[T]]
    val list = new mutable.MutableList[Notification]
    @volatile private var counter: CountDownLatch = new CountDownLatch(awaitCount)

    def onCompleted {
        list += OnCompleted()
        throw new IllegalStateException("Class subscription should not complete.")
    }

    def onError(e: Throwable) {
        list += OnError(e)
        throw new RuntimeException("Got exception from class subscription", e)
    }

    def onNext(value: Observable[T]) {
        val obs = new AwaitableObserver[T](1)
        value.subscribe(obs)
        observers += obs
        counter.countDown()
    }

    def await(duration: Duration, resetCount: Int): Boolean = try {
        counter.await(duration.length, duration.unit)
    } finally {
        counter = new CountDownLatch(resetCount)
    }

    def reset(newCounter: Int) {
        counter = new CountDownLatch(newCounter)
    }
}