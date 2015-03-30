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

import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

import scala.concurrent.duration.Duration

import rx.Observer

object AwaitableObserver {
    private val AWAITING = new Object()
    private val DONE = new Object()
}

/**
 * Trait to be mixed into an Observer that can be awaited.
 * The `awaitCompletion` method blocks the calling thread until the stream
 * finishes or the timeout expired. The `awaitOnNext` awaits a number of onNext
 * events to have happened and returns true or false whether the stream saw the
 * expected number of events or it finished prematurely. It is also subjected to
 * a timeout. A `reset` method is provided in case the Observer instance needs
 * to be reused.
 */
trait AwaitableObserver[T] extends Observer[T] {
    import AwaitableObserver._

    @volatile private var events = 0L
    @volatile private var awaitingCount = 0L
    @volatile private var status = AWAITING
    @volatile private var thread: Thread = _

    abstract override def onNext(value: T): Unit = {
        super.onNext(value)
        events += 1
        val ac = awaitingCount
        if (ac > 0 && (events - ac) == 0) {
            wakeUp()
        }
    }

    abstract override def onCompleted(): Unit = try {
        super.onCompleted()
    } finally {
        status = DONE
        wakeUp()
    }

    abstract override def onError(e: Throwable): Unit = try {
        super.onError(e)
    } finally {
        status = DONE
        wakeUp()
    }

    private def wakeUp(): Unit =
        if (thread ne null) {
            LockSupport.unpark(thread)
        }

    @throws(classOf[TimeoutException])
    def awaitCompletion(timeout: Duration): Unit =
       await(Long.MaxValue, timeout)

    @throws(classOf[TimeoutException])
    def awaitOnNext(expectedEvents: Long, timeout: Duration): Boolean = try {
        awaitingCount = expectedEvents
        await(expectedEvents, timeout)
        events >= expectedEvents
    } finally {
        awaitingCount = 0
    }

    private def await(expectedEvents: Long, timeout: Duration): Unit = {
        var toWait = timeout.toNanos
        thread = Thread.currentThread()
        try {
            do {
                if ((status eq DONE) || events >= expectedEvents)
                    return

                if (timeout != Duration.Inf && toWait < 0)
                    throw new TimeoutException()

                val start = System.nanoTime()
                LockSupport.parkNanos(toWait)
                toWait -= System.nanoTime() - start
            } while (true)
        } finally {
            thread = null
        }
    }

    def reset(): Unit = {
        status = AWAITING
        events = 0
    }
}
