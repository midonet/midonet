/*
 * Copyright 2017 Midokura SARL
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

import java.util.List
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import org.midonet.midolman.logging.MidolmanLogging

object CallbackRegistry {
    type CallbackId = Long

    class CallbackSpec(val id: CallbackId, val args: Array[Byte])

    trait SerializableCallback {
        def call(args: Array[Byte]): Unit
    }
}

trait CallbackRegistry {
    import CallbackRegistry._

    def registerCallback(cb: SerializableCallback): CallbackId
    def unregisterCallback(id: CallbackId): Unit
    def runAndClear(callbacks: List[CallbackSpec]): Unit
}

class CallbackRegistryImpl extends CallbackRegistry with MidolmanLogging {
    override def logSource = s"org.midonet.midolman.callbacks"

    import CallbackRegistry._
    private val idCounter = new AtomicLong(0)
    private val callbackMap = new ConcurrentHashMap[Long, SerializableCallback]

    override def registerCallback(cb: SerializableCallback): CallbackId = {
        val id = idCounter.incrementAndGet()
        assert(callbackMap.put(id, cb) == null)
        log.debug(s"Registered callback $cb with id $id")
        id
    }

    override def unregisterCallback(id: CallbackId): Unit = {
        val cb = callbackMap.remove(id)
        log.debug(s"Unregistered callback $cb, id $id")
    }

    override def runAndClear(callbacks: List[CallbackSpec]): Unit = {
        var i = 0
        while (i < callbacks.size) {
            val spec = callbacks.get(i)
            val cb = callbackMap.get(spec.id)
            if (cb != null) {
                cb.call(spec.args)
            } else {
                log.warn(s"No callback found for id ${spec.id}")
            }
            i += 1
        }
        callbacks.clear()
    }
}


