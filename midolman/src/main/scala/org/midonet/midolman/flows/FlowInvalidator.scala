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

package org.midonet.midolman.flows

import org.jctools.queues.MpscArrayQueue

import org.midonet.midolman.{CheckBackchannels, PacketsEntryPoint}
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.WakerUpper.Parkable

trait InvalidationSource {
    def scheduleInvalidationFor(tag: FlowTag): Unit
}

object FlowInvalidator {
    private val MAX_PENDING_INVALIDATIONS = 1024
}

// TODO: having to pass the actorsService here, ugly as it
//       may be, is an artifact of our bootstrap process
final class FlowInvalidator(actorsService: MidolmanActorsService,
                            numQueues: Int) extends Parkable with InvalidationSource {
    import FlowInvalidator._

    private val queues = new Array[MpscArrayQueue[FlowTag]](numQueues)
    @volatile private var waitingFor = 0

    {
        var i = 0
        while (i < numQueues) {
            queues(i) = new MpscArrayQueue[FlowTag](MAX_PENDING_INVALIDATIONS)
            i += 1
        }
    }

    /**
     * Broadcasts this tag to all invalidation queues. Not thread-safe.
     */
    def scheduleInvalidationFor(tag: FlowTag): Unit = {
        var i = 0
        while (i < numQueues) {
            while (!queues(i).offer(tag)) {
                waitingFor = i
                park(retries = 0)
            }
            i += 1
        }
        PacketsEntryPoint.getRef()(actorsService.system) ! CheckBackchannels
    }

    /**
     * Processes the invalidated tags for the queue identified by the `id`
     * parameter. Thread-safe for callers specifying different ids.
     */
    def process(id: Int, invalidation: FlowInvalidation): Unit = {
        val q = queues(id)
        var tag: FlowTag = null
        while ({ tag = q.poll(); tag } ne null) {
            invalidation.invalidateFlowsFor(tag)
        }
    }

    /**
     * Processes the invalidated tags for all queues. Not thread-safe. Also not
     * thread-safe for concurrent callers of `process`.
     */
    def processAll(invalidation: FlowInvalidation): Unit = {
        var i = 0
        while (i < numQueues) {
            process(i, invalidation)
            i += 1
        }
    }

    def needsToInvalidateTags(id: Int): Boolean = !queues(id).isEmpty

    override def shouldWakeUp(): Boolean = needsToInvalidateTags(waitingFor)
}
