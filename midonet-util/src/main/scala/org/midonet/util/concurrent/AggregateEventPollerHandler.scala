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

package org.midonet.util.concurrent

import com.lmax.disruptor.{LifecycleAware, EventPoller}

class AggregateEventPollerHandler[T](eventHandlers: EventPoller.Handler[T]*)
    extends EventPoller.Handler[T] with LifecycleAware {

    @throws(classOf[Exception])
    override def onEvent(event: T, sequence: Long, endOfBatch: Boolean): Boolean = {
        var i = 0
        var res = true
        while (i < eventHandlers.length) {
            res &= eventHandlers(i).onEvent(event, sequence, endOfBatch)
            i += 1
        }
        res
    }

    override def onStart(): Unit =
        eventHandlers foreach {
            case aware: LifecycleAware =>
                aware.onStart()
            case _ =>
        }

    override def onShutdown(): Unit =
        eventHandlers foreach {
            case aware: LifecycleAware =>
                aware.onShutdown()
            case _ =>
        }
}
