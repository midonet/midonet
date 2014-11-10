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

package org.midonet.cluster.data.storage

import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.collection.mutable

import org.junit.Assert._

import rx.{Observable, Observer}

class ObjectSubscription[T](counter: Int) extends Observer[T] {
    private var countDownLatch = new CountDownLatch(counter)
    var updates: Int = 0
    var event: Option[T] = None
    var ex: Throwable = _

    def onCompleted {
        event = None
        countDownLatch.countDown()
    }

    def onError(e: Throwable) {
        ex = e
        countDownLatch.countDown()
    }

    def onNext(t: T) {
        updates += 1
        event = Option(t)
        countDownLatch.countDown()
    }

    def await(timeout: Long, unit: TimeUnit) {
        assertTrue(countDownLatch.await(timeout, unit))
    }

    def reset(newCounter: Int) {
        countDownLatch = new CountDownLatch(newCounter)
    }
}

class ClassSubscription[T](counter: Int) extends Observer[Observable[T]] {
    private var countDownLatch: CountDownLatch = new CountDownLatch(counter)
    val subs = new mutable.MutableList[ObjectSubscription[T]]


    def onCompleted {
        fail("Class subscription should not complete.")
    }

    def onError(e: Throwable) {
        throw new RuntimeException("Got exception from class subscription", e)
    }

    def onNext(observable: Observable[T]) {
        val sub = new ObjectSubscription[T](1)
        observable.subscribe(sub)
        subs += sub
        countDownLatch.countDown()
    }

    def await(timeout: Long, unit: TimeUnit) {
        assertTrue(countDownLatch.await(timeout, unit))
    }

    def reset(newCounter: Int) {
        countDownLatch = new CountDownLatch(newCounter)
    }
}
