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
        var i = 0
        while (i < callbacks.size()) {
            queue.add(callbacks.get(i))
            i += 1
        }
    }

    def run(): Unit = {
        var cb: Callback0 = null
        while ({cb = queue.poll(); cb ne null}) {
            cb.call()
        }
    }
}
