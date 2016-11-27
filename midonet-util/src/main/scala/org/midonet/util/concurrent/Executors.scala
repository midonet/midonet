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

package org.midonet.util.concurrent

import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.concurrent._

import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Utility class for creating custom executors.
  */
object Executors {

    val CallerRunsPolicy = new CallerRunsPolicy()

    /**
      * @return A single-threaded scheduled executor.
      */
    def singleThreadScheduledExecutor(name: String, isDaemon: Boolean,
                                      handler: RejectedExecutionHandler)
    : ScheduledExecutorService = {
        new ScheduledThreadPoolExecutor(
            1, new ThreadFactory {
                override def newThread(runnable: Runnable): Thread = {
                    val thread = new Thread(runnable, name)
                    thread.setDaemon(isDaemon)
                    thread
                }
            }, handler)
    }

    /**
      * @return An unbounded cached executor pool.
      */
    def cachedPoolExecutor(name: String, isDaemon: Boolean,
                           handler: RejectedExecutionHandler)
    : ExecutorService = {
        new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue[Runnable](),
            new NamedThreadFactory(name, isDaemon = isDaemon),
            handler)
    }

    /**
      * Shuts down the given executor.
      */
    def shutdown(executor: ExecutorService)
                (onError: (Throwable) => Unit)
                (implicit timeout: Duration = 1 second): Unit = {
        try {
            executor.shutdown()
            if (timeout.isFinite() &&
                !executor.awaitTermination(timeout.toMillis,
                                           TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        } catch {
            case e: InterruptedException =>
                try onError(e) catch { case NonFatal(_) => }
        }
    }

}
