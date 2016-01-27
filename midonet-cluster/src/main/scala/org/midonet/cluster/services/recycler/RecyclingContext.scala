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

package org.midonet.cluster.services.recycler

import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import java.util.concurrent.{TimeUnit, CountDownLatch, ConcurrentHashMap, ScheduledExecutorService}

import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.RecyclerConfig
import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.util.UnixClock

/**
  * Contains the context for a recycling operation. An instance of this
  * class contains the state variable for a recycling operation, including
  * the start and finish timestamps, and the NSDB entries that have been
  * recycled (namespaces, objects, state paths).
  */
class RecyclingContext(val config: RecyclerConfig,
                       val curator: CuratorFramework,
                       val store: ZookeeperObjectMapper,
                       val executor: ScheduledExecutorService,
                       val clock: UnixClock,
                       val log: Logger,
                       val interval: Duration) {

    val start = clock.time
    var version = 0
    var timestamp = 0L
    @volatile var canceled = false
    @volatile var executed = false

    var hosts: Set[String] = null
    var namespaces: Set[String] = null

    val deletedNamespaces = new AtomicInteger
    val failedNamespaces = new AtomicInteger

    val deletedObjects = new AtomicInteger
    val failedObjects = new AtomicInteger

    val modelObjects = new ConcurrentHashMap[Class[_], Set[String]]()
    val stateObjects = new ConcurrentHashMap[(String, Class[_]), Seq[String]]()

    private val latch = new CountDownLatch(1)

    private val nsdbInterval = if (config.throttlingRate == 0) 0L
                               else 1000000000L / config.throttlingRate
    private val nsdbLast = new AtomicLong(clock.timeNanos)

    private val stepIndex = new AtomicInteger
    private final val stepCount = 7

    /**
      * Cancels the recycling task for the current context.
      */
    def cancel(): Unit = {
        canceled = true
    }

    /**
      * Returns the current recycling step as string.
      */
    def step(): String = {
        s"(step ${stepIndex.incrementAndGet()} of $stepCount)"
    }

    /**
      * @return The duration of the current recycling operation.
      */
    def duration: Long = {
        clock.time - start
    }

    /**
      * Indicates the completion of the recycling task corresponding to the
      * current context.
      */
    def complete(): Unit = {
        latch.countDown()
    }

    /**
      * @return True if the recycling task corresponding to this context has
      *         completed.
      */
    def completed: Boolean = {
        latch.getCount == 0
    }

    /**
      * Awaits the completion of the task corresponding to this context.
      */
    def awaitCompletion(duration: Duration): Boolean = {
        latch.await(duration.toMillis, TimeUnit.MILLISECONDS)
    }

    /**
      * Computes the delay in nanoseconds for the next NSDB write operation,
      * according to the NSDB throttling configuration.
      */
    def nsdbDelayNanos(): Long = {
        // No throttling: return zero.
        if (nsdbInterval == 0L)
            return 0L

        // Loop until we manage to increment the last NSDB atomic counter.
        while (true) {
            // Compute the delay with respect to the last NSDB operation. The
            // delay is the timestamp of the last write + the throttling
            // interval between writes - current time. If the delay is negative
            // or zero, we schedule immediately and set the counter to the
            // current time. If the delay is positive, we schedule after delay
            // and set the counter at the current time + delay.
            val now = clock.timeNanos
            val last = nsdbLast.get()
            val delay = last + nsdbInterval - now
            if (delay <= 0) {
                if (nsdbLast.compareAndSet(last, now)) {
                    return 0L
                }
            } else {
                if (nsdbLast.compareAndSet(last, now + delay)) {
                    return delay
                }
            }
        }
        0L
    }
}
