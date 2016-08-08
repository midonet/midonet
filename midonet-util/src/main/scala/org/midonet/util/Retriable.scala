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

import scala.concurrent.duration.Duration
import scala.util.Random
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting

import org.slf4j.Logger

trait Retriable {

    /** Max number of attempts before giving up after reaching the max delay */
    def maxRetries: Int

    def retry[T](log: Logger, message: String)
                (retriable: => T): Either[Throwable, T] = {
        retry(maxRetries, log, message)(retriable)
    }

    protected def retry[T](retries: Int, log: Logger, message: String)
                          (retriable: => T): Either[Throwable, T] = {
        try {
            Right(retriable)
        } catch {
            case NonFatal(e) if retries > 1 =>
                handleRetry(retries, log, message)(retriable)
            case NonFatal(e) =>
                log debug s"$message failed after $maxRetries attempts. " +
                          s"Giving up. ${e.getMessage}"
                Left(e)
        }

    }

    def handleRetry[T](retries: Int, log: Logger, message: String)
                      (retriable: => T): Either[Throwable, T]

}

trait ImmediateRetriable extends Retriable {

    def handleRetry[T](retries: Int, log: Logger, message: String)
                      (retriable: => T): Either[Throwable, T] = {
        log debug s"$message failed. Remaining retries: ${retries - 1}"
        retry(retries - 1, log, message) (retriable)
    }
}

trait AwaitRetriable extends Retriable {

    /** Time to wait between retries */
    def interval: Duration

    def handleRetry[T](retries: Int, log: Logger, message: String)
                      (retriable: => T): Either[Throwable, T] = {
        log debug s"$message failed. Remaining retries: ${retries - 1}. " +
                  s"Retrying in ${interval toMillis} ms."
        await(interval toMillis)
        retry(retries - 1, log, message) (retriable)
    }

    @VisibleForTesting
    protected def await(timeout: Long) = Thread.sleep(timeout)
}

trait ExponentialBackoffRetriable extends Retriable {

    /** Interval delay to start with */
    def interval: Duration

    /** Maximum waiting time in milliseconds */
    def maxDelay: Duration

    def handleRetry[T](retries: Int, log: Logger, message: String)
                      (retriable: => T): Either[Throwable, T] = {

        val backoff = Random.nextInt(backoffTime(maxRetries - retries))
        log debug s"$message failed. Remaining attempts: ${retries -1}. " +
                  s"Retrying in $backoff ms."
        await(backoff)
        retry(retries - 1, log, message)(retriable)
    }

    @VisibleForTesting
    protected def await(timeoutInterval: Int) = Thread.sleep(timeoutInterval)

    @inline
    protected def backoffTime(attempt: Int): Int = {
        Math.min(maxDelay toMillis, (1 << attempt) * interval.toMillis).toInt
    }
}

trait ClosingRetriable extends Retriable {

    def retryClosing[T](log: Logger, message: String)
                       (closeable: Closeable)
                       (retriable: => T): Either[Throwable, T] = {
        try {
            retry(log, message) { retriable }
        } finally {
            if (closeable ne null) closeable.close()
        }
    }
}