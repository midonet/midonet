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
package org.midonet.sdn

import java.util.concurrent.locks.LockSupport
import java.util.{ArrayList, LinkedList, Queue}

import org.midonet.util.functors.Callback0

// A wrapper class that enqueues callbacks in the specified queue.
object CallbackExecutor {
    val Immediate = new CallbackExecutor(new LinkedList()) {
        override def schedule(callbacks: ArrayList[Callback0]): Unit = {
            super.schedule(callbacks)
            run()
        }
    }
}

sealed class CallbackExecutor(queue: Queue[Callback0]) {
    def schedule(callbacks: ArrayList[Callback0]): Unit = {
        var retries = 200
        var i = 0
        while (i < callbacks.size()) {
            val cb = callbacks.get(i)
            while (!queue.add(cb)) {
                retries = doWait(retries)
            }
            i += 1
        }
    }

    private def doWait(retries: Int): Int =
        if (retries > 100) {
            retries - 1
        } else if (retries > 0) {
            Thread.`yield`()
            retries - 1
        } else {
            LockSupport.parkNanos(1L)
            retries
        }

    def run(): Unit = {
        var cb: Callback0 = null
        while ({cb = queue.poll(); cb } ne null) {
            cb.call()
        }
    }
}
