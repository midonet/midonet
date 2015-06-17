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

import org.midonet.midolman.{BackChannelHandler, BackChannelMessage, SimulationBackChannel, ShardedSimulationBackChannel}
import org.midonet.midolman.cluster.MidolmanModule
import org.midonet.midolman.state.{MockNatBlockAllocator, NatBlockAllocator}
import org.midonet.util.collection.IPv4InvalidationArray

class MockMidolmanModule extends MidolmanModule {
    protected override def bindSimulationBackChannel(): Unit = {
        bind(classOf[SimulationBackChannel]).toInstance(new  SimulationBackChannel() {
            private val q = new LinkedList[BackChannelMessage]()

            override def tell(msg: BackChannelMessage): Unit = q.offer(msg)

            override def hasMessages: Boolean = !q.isEmpty

            override def process(handler: BackChannelHandler): Unit =
                while (hasMessages) {
                    handler.handle(q.pop())
                }
        })
        expose(classOf[SimulationBackChannel])
        bind(classOf[ShardedSimulationBackChannel]).toInstance(new ShardedSimulationBackChannel(null))
        expose(classOf[ShardedSimulationBackChannel])

        IPv4InvalidationArray.reset()
    }

    protected override def bindAllocator() {
        bind(classOf[NatBlockAllocator]).toInstance(new MockNatBlockAllocator)
        expose(classOf[NatBlockAllocator])
    }
}
