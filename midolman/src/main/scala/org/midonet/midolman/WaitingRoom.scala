/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
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

    private[this] val waiters = new mutable.HashSet[W]()
    private[this] val timeouts = new mutable.ListBuffer[(W, Long)]()

    /**
     * Number of waiters currently in the room.
     */
    def count = waiters.size

    /**
     * Adds a new waiter w that will be kept here for a max of TIMEOUT nanos.
     *
     * If the element is already in the waiting room, it will not be added again
     * with the *old* waiting time unaltered.
     */
    def enter(w: W): IndexedSeq[W] = {
        val evictions = doExpirations()
        if (!(waiters contains w)) {
            waiters += w
            timeouts += ((w, System.nanoTime() + timeout))
        }
        evictions
    }

    def leave(w: W): Unit = {
        waiters -= w
    }

    def doExpirations(): IndexedSeq[W] = {
        var evictions: mutable.ArrayBuffer[W] = null
        val now = System.nanoTime()
        while (timeouts.nonEmpty && (now - timeouts.head._2) > 0) {
            val w = (timeouts remove 0)._1
            if (waiters contains w) {
                if (evictions == null)
                    evictions = mutable.ArrayBuffer()
                evictions += w
                waiters -= w
            }
        }

        if (evictions == null)
            immutable.Vector.empty
        else
            evictions
    }
}
