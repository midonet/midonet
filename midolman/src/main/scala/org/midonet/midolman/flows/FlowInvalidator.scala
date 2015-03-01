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

import com.google.inject.Inject
import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.CheckCompletedRequests
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.WakerUpper.Parkable
import rx.internal.util.unsafe.SpscArrayQueue

object FlowInvalidator {
    private val MAX_PENDING_INVALIDATIONS = 1024
}

// TODO: having to pass the actorsService here, ugly as it
//       may be, is an artifact of our bootstrap process
final class FlowInvalidator @Inject() (actorsService: MidolmanActorsService) extends Parkable {
    import FlowInvalidator._

    private val queue = new SpscArrayQueue[FlowTag](MAX_PENDING_INVALIDATIONS)

    def scheduleInvalidationFor(tag: FlowTag): Unit = {
        while (!queue.offer(tag)) {
            park(retries = 0)
        }
        FlowController.getRef()(actorsService.system) ! CheckCompletedRequests
    }

    def process(invalidation: FlowInvalidation): Unit = {
        var tag: FlowTag = null
        while ({ tag = queue.poll(); tag } ne null) {
            invalidation.invalidateFlowsFor(tag)
        }
    }

    override def shouldWakeUp(): Boolean =
        queue.size() < MAX_PENDING_INVALIDATIONS
}
