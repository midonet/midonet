/*
 * Copyright (c) 2015 Midokura SARL
 */

package org.midonet.util.concurrent

import java.util.concurrent.{ExecutorService, TimeUnit}

import scala.concurrent.duration.Duration

/**
 * Some utility methods to handle executors
 */
object ThreadHelpers {
    /** Try to shutdown a thread gracefully... or forcefully */
    def terminate(thread: ExecutorService, timeout: Duration,
                  now: Boolean = false): Boolean = {
        if (thread != null) {
            if (!now)
                thread.shutdown()
            if (now || !thread.awaitTermination(timeout.toMillis,
                                                TimeUnit.MILLISECONDS)) {
                thread.shutdownNow()
                thread.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS)
            } else true
        } else true
    }
}
