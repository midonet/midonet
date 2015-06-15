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

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.util.ClassAwaitableObserver.ChildObserver
import org.midonet.util.reactivex.AwaitableObserver

object ClassAwaitableObserver {
    class ChildObserver[T] extends AwaitableObserver[T]
}

class ClassAwaitableObserver[T](awaitCount: Int) extends TestObserver[Observable[T]] {

    val observers = new mutable.MutableList[ChildObserver[T]]
    @volatile private var counter: CountDownLatch = new CountDownLatch(awaitCount)

    override def onNext(value: Observable[T]) {
        super.onNext(value)
        val obs = new ChildObserver[T]
        observers += obs
        value.subscribe(obs)
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
