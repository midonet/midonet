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

import java.util.ArrayList

import org.jctools.queues.MpscArrayQueue

import org.midonet.midolman.{CheckBackchannels, PacketsEntryPoint}
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.WakerUpper.Parkable

trait InvalidationSource {
    def scheduleInvalidationFor(tag: FlowTag): Unit
}

trait InvalidationTarget {
    def process(invalidation: FlowInvalidation): Unit
    def hasInvalidations: Boolean
}

object FlowInvalidator {
    private val MAX_PENDING_INVALIDATIONS = 1024

    private final class FlowInvalidationQueue extends InvalidationTarget {
        private[FlowInvalidator] val q =
            new MpscArrayQueue[FlowTag](MAX_PENDING_INVALIDATIONS)

        /**
         * Processes the invalidated tags for the queue identified by the `id`
         * parameter. Thread-safe for callers specifying different ids.
         */
        override def process(invalidation: FlowInvalidation): Unit = {
            var tag: FlowTag = null
            while ({ tag = q.poll(); tag } ne null) {
                invalidation.invalidateFlowsFor(tag)
            }
        }

        override def hasInvalidations: Boolean =
            q.size() < MAX_PENDING_INVALIDATIONS
    }
}

// TODO: having to pass the actorsService here, ugly as it
//       may be, is an artifact of our bootstrap process
final class FlowInvalidator(actorsService: MidolmanActorsService)
        extends Parkable with InvalidationSource {
    import FlowInvalidator._

    private val queues = new ArrayList[FlowInvalidationQueue]()
    @volatile private var waitingFor = 0

    def registerInvalidationTarget(): InvalidationTarget = {
        val invQ = new FlowInvalidationQueue()
        queues.add(invQ)
        invQ
    }

    /**
     * Broadcasts this tag to all invalidation queues. Not thread-safe.
     */
    def scheduleInvalidationFor(tag: FlowTag): Unit = {
        var i = 0
        while (i < queues.size()) {
            val q = queues.get(i).q
            while (!q.offer(tag)) {
                waitingFor = i
                park(retries = 0)
            }
            i += 1
        }
        PacketsEntryPoint.getRef()(actorsService.system) ! CheckBackchannels
    }

    /**
     * Processes the invalidated tags for all queues. Not thread-safe. Also not
     * thread-safe for concurrent callers of `process`.
     */
    def processAll(invalidation: FlowInvalidation): Unit = {
        var i = 0
        while (i < queues.size()) {
            queues.get(i).process(invalidation)
            i += 1
        }
    }

    override def shouldWakeUp(): Boolean =
        queues.get(waitingFor).hasInvalidations
}
