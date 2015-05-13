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

package org.midonet.midolman.util.guice

import java.util.LinkedList

import org.midonet.midolman.cluster.MidolmanModule
import org.midonet.midolman.flows.{ShardedFlowInvalidator, FlowInvalidationHandler, FlowInvalidator}
import org.midonet.midolman.state.{MockNatBlockAllocator, NatBlockAllocator}
import org.midonet.sdn.flows.FlowTagger.FlowTag

class MockMidolmanModule extends MidolmanModule {

    protected override def bindFlowInvalidator: Unit = {
        bind(classOf[FlowInvalidator]).toInstance(new FlowInvalidator {
            private val q = new LinkedList[FlowTag]()

            override def scheduleInvalidationFor(tag: FlowTag): Unit =
                q.offer(tag)

            override def hasInvalidations: Boolean =
                !q.isEmpty

            override def process(handler: FlowInvalidationHandler): Unit =
                while (hasInvalidations) {
                    handler.invalidateFlowsFor(q.pop())
                }
        })
        expose(classOf[FlowInvalidator])
        bind(classOf[ShardedFlowInvalidator]).toInstance(new ShardedFlowInvalidator(null))
        expose(classOf[ShardedFlowInvalidator])
    }

    protected override def bindAllocator() {
        bind(classOf[NatBlockAllocator]).toInstance(new MockNatBlockAllocator)
        expose(classOf[NatBlockAllocator])
    }
}
