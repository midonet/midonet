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

import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

trait Retriable {

    def retry[T](retries: Int, log: Logger, message: String)
                (retriable: => T): Either[Throwable, T] = {
        try {
            Right(retriable)
        } catch {
            case NonFatal(e) if retries > 1 =>
                log debug s"$message. Retrying. Remaining attempts: ${retries-1}"
                retry (retries - 1, log, message) (retriable)
            case NonFatal(e) =>
                log debug s"$message. Giving up."
                Left(e)
        }
    }
}

trait ClosingRetriable extends Retriable {

    def retryClosing[T](retries: Int, log: Logger, message: String)
                       (closeable: Closeable)
                       (retriable: => T): Either[Throwable, T] = {
        try {
            retry(retries, log, message) { retriable }
        } finally {
            if (closeable ne null) closeable.close()
        }
    }
}
