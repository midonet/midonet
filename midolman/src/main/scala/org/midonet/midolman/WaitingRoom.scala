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
package org.midonet.midolman

import scala.collection.{immutable, mutable}
import java.util.concurrent.TimeUnit

/**
 * A WaitingRoom is an abstraction that allows holding Waiters for a limited
 * amount of time. Waiters are guaranteed to stay in the room for *at least*
 * the given waiting time. Once the time is fulfilled they will be made to leave
 * the room at any later time. Whenever this happens, users may get a
 * notification through the "leave" callback.
 *
 * This class is not thread safe, and all instances expected to be confined to
 * a thread.
 *
 * @param timeout timeout, in nanoseconds
 */
class WaitingRoom[W](val timeout: Long = TimeUnit.SECONDS.toNanos(3)) {

    private[this] val waiters = new java.util.HashSet[W]()
    private[this] val timeouts = new mutable.ListBuffer[(W, Long)]()

    /**
     * Number of waiters currently in the room.
     */
    def count = waiters.size

    /**
     * Adds a new waiter that will be kept here for a min of TIMEOUT nanos.
     *
     * If the element is already in the waiting room, it will not be added again
     * and the *old* waiting time remains unaltered.
     */
    def enter(w: W): Boolean =
        if (waiters.add(w)) {
            timeouts += ((w, System.nanoTime() + timeout))
            true
        } else {
            false
        }

    def leave(w: W): Unit = {
        waiters.remove(w)
    }

    def doExpirations(f: W => Unit): Unit = {
        val now = System.nanoTime()
        while (timeouts.nonEmpty && (now - timeouts.head._2) > 0) {
            val w = (timeouts remove 0)._1
            if (waiters.remove(w)) {
                f(w)
            }
        }
    }
}
