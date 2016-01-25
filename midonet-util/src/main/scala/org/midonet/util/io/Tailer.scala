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

package org.midonet.util.io

import java.io._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

import scala.concurrent.{Promise, Future}
import scala.util.control.NonFatal

import org.apache.commons.io.IOUtils

import rx.Observer

/**
  * Simple implementation of the UNIX `tail -f` functionality, similar to
  * [[org.apache.commons.io.input.Tailer]], except that this class works on
  * a [[ScheduledExecutorService]] that can be shared between multiple
  * [[Tailer]] instances.
  *
  * @param file The file to follow.
  * @param executor A scheduler executor service, on which the read operations
  *                 are performed.
  * @param observer An [[Observer]] that receives notifications when reading
  *                 a new line from file.
  * @param delay The polling interval.
  * @param unit The polling interval unit.
  * @param bufferSize The reader buffer size.
  * @param taskLimit The maximum interval in milliseconds the tailer is allowed
  *                  to continuously read from the input file. If the limit is
  *                  reached, and the tailer has not reached the end of the
  *                  file, the trailer will yield the executor thread and
  *                  schedule itself to run immediately to continue reading.
  */
class Tailer(file: File, executor: ScheduledExecutorService,
             observer: Observer[String], delay: Long, unit: TimeUnit,
             bufferSize: Int = 0xFFFF,
             taskLimit: Int = 500) {

    private var reader: BufferedReader = null
    private val running = new AtomicBoolean()

    private val readRunnable = new Runnable {
        override def run(): Unit = {
            try {
                if (reader eq null) {
                    reader = new BufferedReader(
                                 new InputStreamReader(
                                     new FileInputStream(file)),
                                 bufferSize)
                }
                if (reader ne null) {
                    val start = System.currentTimeMillis()
                    while(running.get() && reader.ready()) {
                        val line = reader.readLine()
                        if (line ne null) {
                            observer onNext line
                        } else return
                        // Prevent a single tailer blocking the executor thread
                        // for long intervals: if the task duration is reached,
                        // yield the thread and reschedule immediately.
                        if (System.currentTimeMillis() > start + taskLimit) {
                            executor.schedule(this, 0L, unit)
                            return
                        }
                    }
                }
            } catch {
                case NonFatal(e) => observer onError e
            } finally {
                executor.schedule(this, delay, unit)
            }
        }
    }

    private def closeRunnable(promise: Promise[Unit]) = new Runnable {
        override def run(): Unit = {
            try {
                if (reader ne null) {
                    IOUtils.closeQuietly(reader)
                }
                observer.onCompleted()
            } catch {
                case NonFatal(e) => observer onError e
            } finally {
                promise.trySuccess(())
            }
        }
    }

    /**
      * Starts the tailer monitoring the file.
      */
    def start(): Unit = {
        if (running.compareAndSet(false, true)) {
            executor.schedule(readRunnable, 0L, TimeUnit.SECONDS)
        }
    }

    /**
      * Closes asynchronously the tailer and the underlying file reader. The
      * method returns a [[Future]] that completes when the tailer has been
      * closed.
      */
    def close(): Future[Unit] = {
        if (running.compareAndSet(true, false)) {
            val promise = Promise[Unit]()
            executor.schedule(closeRunnable(promise), 0L, TimeUnit.SECONDS)
            promise.future
        } else Future.successful(())
    }

}
