/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.util

import java.io.Closeable
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit.MILLISECONDS

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.Random
import scala.util.control.NonFatal

import org.slf4j.Logger


trait Retriable {

    /**
      * Max number of retries before giving up after reaching the max delay.
      */
    def maxRetries: Int

    /**
      * When overriden in a derived class, this allows to customize the handling
      * on the [[Throwable]] thrown by the retriable function.
      */
    protected def handleRetry(e: Throwable, retries: Int, log: Logger,
                              message: String): Unit

    /**
      * When override in a derived class, this allows to customize how the next
      * retry will be handled.
      */
    protected def schedule(delay: Long): Unit

}

trait BlockingRetriable extends Retriable {

    /**
      * Calls the specified `retriable` function until the function completes
      * without throwing an exception, or until the function is called a number
      * of times equal to [[maxRetries]] + 1. The method returns the last
      * result returned by the `retriable` function or it throws the last
      * [[Throwable]].
      */
    @throws[Throwable]
    def retry[T](log: Logger, message: String)(retriable: => T): T = {
        retry(maxRetries, log, message)(retriable)
    }

    @throws[Throwable]
    @tailrec
    private def retry[T](retries: Int, log: Logger, message: String)
                        (retriable: => T): T = {
        try return retriable
        catch {
            case NonFatal(e) if retries > 0 =>
                handleRetry(e, retries, log, message)
            case NonFatal(e) =>
                log debug s"$message failed after $maxRetries attempts. " +
                          s"Giving up. ${e.getMessage}"
                throw e
        }
        retry(retries - 1, log, message)(retriable)
    }

    override protected def schedule(delay: Long): Unit = Thread.sleep(delay)

}

trait NonBlockingRetriable extends Retriable {

    private var runnable: Runnable = _

    private var executor: ScheduledExecutorService = _

    /**
      * Calls the specified `retriable` function until the function completes
      * without throwing an exception, or until the function is called a number
      * of times equal to [[maxRetries]] + 1. The method returns the last
      * result returned by the `retriable` function or it throws the last
      * [[Throwable]]. All retries are handled in a separate thread passed
      * by parameter to not block the calling thread.
      */
    @throws[Throwable]
    def retry[T](log: Logger, message: String)(executor: ScheduledExecutorService)
                (retriable: => T): Future[T] = {
        val promise = Promise[T]
        this.executor = executor
        runnable = new Runnable {
            @volatile private var retries = maxRetries
            override def run(): Unit = {
                retry[T](retries, log, message, promise)(retriable)
                retries -= 1
            }
        }
        executor.execute(runnable)
        promise.future
    }

    private def retry[T](retries: Int, log: Logger, message: String, promise: Promise[T])
                        (retriable: => T): Unit = {
        try {
            val result = retriable
            promise.trySuccess(result)
        }
        catch {
            case NonFatal(e) if retries > 0 =>
                handleRetry(e, retries, log, message)
            case NonFatal(e) =>
                log debug s"$message failed after $maxRetries attempts. " +
                          s"Giving up. ${e.getMessage}"
                promise.tryFailure(e)
        }
    }

    override protected def schedule(delay: Long) = {
        executor.schedule(runnable, delay, MILLISECONDS)
    }
}

trait ImmediateRetriable extends Retriable {

    protected abstract override def handleRetry(e: Throwable, retries: Int,
                                                log: Logger, message: String)
    : Unit = {
        log debug s"$message failed. Remaining retries: ${retries - 1}"
        super.handleRetry(e, retries, log, message)
    }
}

trait AwaitRetriable extends Retriable {

    /** Time to wait between retries */
    def interval: Duration

    protected abstract override def handleRetry(e: Throwable, retries: Int,
                                                log: Logger, message: String)
    : Unit = {
        log debug s"$message failed. Remaining retries: ${retries - 1}. " +
                  s"Retrying in ${interval toMillis} ms."
        super.handleRetry(e, retries, log, message)
        schedule(interval toMillis)
    }
}

trait ExponentialBackoffRetriable extends Retriable {

    /** Interval delay to start with */
    def interval: Duration

    /** Maximum waiting time in milliseconds */
    def maxDelay: Duration

    protected abstract override def handleRetry(e: Throwable, retries: Int,
                                                log: Logger, message: String)
    : Unit = {
        val backoff = Random.nextInt(backoffTime(maxRetries - retries))
        log debug s"$message failed. Remaining attempts: ${retries -1}. " +
                  s"Retrying in $backoff ms."
        super.handleRetry(e, retries, log, message)
        schedule(backoff)
    }

    @inline
    protected def backoffTime(attempt: Int): Int = {
        Math.min(maxDelay toMillis, (1 << attempt) * interval.toMillis).toInt
    }
}

trait ClosingRetriable extends BlockingRetriable {

    def retryClosing[T](log: Logger, message: String)
                       (closeable: Closeable)
                       (retriable: => T): T = {
        try {
            retry(log, message) { retriable }
        } finally {
            if (closeable ne null) closeable.close()
        }
    }
}

trait DefaultRetriable extends BlockingRetriable {

    protected override def handleRetry(e: Throwable, r: Int, log: Logger,
                                       message: String): Unit = { }

}

trait NonBlockingDefaultRetriable extends NonBlockingRetriable {

    protected override def handleRetry(e: Throwable, r: Int, log: Logger,
                                       message: String): Unit = { }

}
