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

trait FlowInvalidator {
    def scheduleInvalidationFor(tag: FlowTag): Unit
    def hasInvalidations: Boolean
    def process(handler: FlowInvalidationHandler): Unit
}

trait FlowInvalidationHandler {
    def invalidateFlowsFor(tag: FlowTag): Unit
}

// TODO: having to pass the actorsService here, ugly as it
//       may be, is an artifact of our bootstrap process
final class ShardedFlowInvalidator(actorsService: MidolmanActorsService)
    extends FlowInvalidator {

    private val NO_SHARD: FlowInvalidatorShard = null
    private val processors = new ArrayList[FlowInvalidatorShard]()

    def registerProcessor(): FlowInvalidatorShard = {
        val processor = new FlowInvalidatorShard()
        processors.add(processor)
        processor
    }

    /**
     * Broadcasts this tag to all invalidation processors.
     */
    override def scheduleInvalidationFor(tag: FlowTag): Unit =
        scheduleInvalidationInOthers(NO_SHARD, tag)

    private def scheduleInvalidationInOthers(
            shardToSkip: FlowInvalidatorShard,
            tag: FlowTag): Unit = {
        var i = 0
        while (i < processors.size()) {
            val p = processors.get(i)
            if (p ne shardToSkip)
                p.scheduleInvalidationFor(tag)
            i += 1
        }
        PacketsEntryPoint.getRef()(actorsService.system) ! CheckBackchannels
    }

    override def hasInvalidations: Boolean = {
        var i = 0
        while (i < processors.size()) {
            if (processors.get(i).hasInvalidations)
                return true
            i += 1
        }
        false
    }

     override def process(handler: FlowInvalidationHandler): Unit =
        throw new Exception("Calling process on the main flow invalidator is not supported")

    final class FlowInvalidatorShard extends FlowInvalidator with Parkable {
        private val MAX_PENDING_INVALIDATIONS = 1024

        private val q = new MpscArrayQueue[FlowTag](MAX_PENDING_INVALIDATIONS)

        /**
         * Broadcasts this tag to all invalidation processors.
         */
        override def scheduleInvalidationFor(tag: FlowTag): Unit = {
            while (!q.offer(tag)) {
                park(retries = 0)
            }
            scheduleInvalidationInOthers(this, tag)
        }

        override def hasInvalidations: Boolean =
            q.size() < MAX_PENDING_INVALIDATIONS

        /**
         * Processes the invalidations in this instance.
         */
        override def process(handler: FlowInvalidationHandler): Unit = {
            var tag: FlowTag = null
            while ({ tag = q.poll(); tag } ne null) {
                handler.invalidateFlowsFor(tag)
            }
        }

        override def shouldWakeUp(): Boolean =
            hasInvalidations
    }
}
