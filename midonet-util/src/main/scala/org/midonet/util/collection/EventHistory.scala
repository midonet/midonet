/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.util.collection

import java.{util => ju}

import org.midonet.Util

object EventHistory {
    sealed trait EventSearchResult
    case object EventSeen extends EventSearchResult
    case object EventNotSeen extends EventSearchResult
    case object EventSearchWindowMissed extends EventSearchResult
}

class EventHistory[T](minCapacity: Int)
                     (implicit m: Manifest[T]) {
    import EventHistory._

    val capacity = Util.findNextPositivePowerOfTwo(minCapacity)
    private val mask = capacity - 1
    private var pos: Long = 0L
    private val events = new Array[T](capacity)

    // Safe to publish to other threads; they will at worse see
    // an outdated value and discard a simulation result
    def latest: Long = pos - 1

    def put(event: T): Unit = {
        val idx = (pos & mask).toInt
        events(idx) = event
        pos += 1
    }

    def existsSince(lastSeen: Long, eventSet: ju.Set[T]): EventSearchResult = {
        var i = lastSeen + 1

        if (pos - capacity > i)
            return EventSearchWindowMissed

        while (i < pos) {
            val idx = (i & mask).toInt
            if (eventSet.contains(events(idx)))
                return EventSeen
            i = i + 1
        }
        EventNotSeen
    }
}
