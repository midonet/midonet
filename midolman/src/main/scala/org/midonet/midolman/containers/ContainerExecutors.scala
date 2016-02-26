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

package org.midonet.midolman.containers

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent._

import org.midonet.midolman.config.ContainerConfig

/**
  * A pool of executors for the containers service. An instance of this class
  * creates a number of single threaded executors equal to the number set in the
  * configuration or the number of physical processors available if the
  * configured value is zero.
  *
  * Upon each call of the `nextExecutor` method, the class returns an executor
  * from the pool, in incremental cyclic order, such that each consecutive call
  * receives a different executor if more that one is available.
  */
class ContainerExecutors(config: ContainerConfig) {

    private var threadCount = config.threadCount
    if (threadCount <= 0) {
        threadCount = Runtime.getRuntime.availableProcessors()
    }
    if (threadCount > 0) {
        threadCount = 1 << (32 - Integer.numberOfLeadingZeros(threadCount - 1))
    }
    private val threadCountMask = threadCount - 1

    private val executors = new Array[ExecutorService](threadCount)
    for (index <- 0 until threadCount) {
        executors(index) = newExecutor(index)
    }

    private val counter = new AtomicInteger

    /**
      * @return The number of threads in this executor pool.
      */
    def count = threadCount

    /**
      * Returns the next executor from the executor pool.
      */
    def nextExecutor(): ExecutorService = {
        executors(counter.getAndIncrement() & threadCountMask)
    }

    /**
      * Shuts down all executors from this executor pool.
      */
    def shutdown(): Unit = {
        for (executor <- executors) {
            executor.shutdown()
        }
        for (executor <- executors) {
            if (!executor.awaitTermination(config.shutdownGraceTime.toMillis,
                                           TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        }
    }

    /**
      * Returns a new executor for the specified index in the executors table.
      * This method can be overridden in a derived class to change the
      * executor type.
      */
    protected def newExecutor(index: Int): ExecutorService = {
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
            override def newThread(runnable: Runnable): Thread = {
                val thread = new Thread(runnable, s"container-$index")
                thread.setDaemon(true)
                thread
            }
        })
    }

}
