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

import scala.collection.mutable
import scala.concurrent.duration.Duration

import rx.{Observable, Observer}

object AwaitableObserver {

    trait Notification
    case class OnNext[T](value: T) extends Notification
    case class OnCompleted() extends Notification
    case class OnError(e: Throwable) extends Notification

}

class AwaitableObserver[T](awaitCount: Int = 1) extends Observer[T] {

    import org.midonet.util.reactivex.AwaitableObserver._

    val list = new mutable.MutableList[Notification]
    var ex: Throwable = _
    @volatile private var counter = new CountDownLatch(awaitCount)

    override def onNext(value: T): Unit = {
        list += OnNext(value)
        counter.countDown()
    }

    override def onCompleted(): Unit = {
        list += OnCompleted()
        counter.countDown()
    }

    override def onError(e: Throwable): Unit = {
        ex = e
        list += OnError(e)
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

    def notifications: Seq[Notification] = list

}


class ClassAwaitableObserver[T](awaitCount: Int) extends Observer[Observable[T]] {

    val list = new mutable.MutableList[AwaitableObserver[T]]
    @volatile private var counter: CountDownLatch = new CountDownLatch(awaitCount)

    def onCompleted {
        throw new IllegalStateException("Class subscription should not complete.")
    }

    def onError(e: Throwable) {
        throw new RuntimeException("Got exception from class subscription", e)
    }

    def onNext(value: Observable[T]) {
        val obs = new AwaitableObserver[T](1)
        value.subscribe(obs)
        list += obs
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
