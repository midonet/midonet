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
import java.util.concurrent.ExecutorService

import com.google.common.util.concurrent.MoreExecutors
import com.google.inject.name.Names

import org.midonet.midolman.services.SelectLoopService
import org.midonet.midolman.{BackChannelHandler, BackChannelMessage, SimulationBackChannel, ShardedSimulationBackChannel}
import org.midonet.midolman.cluster.MidolmanModule
import org.midonet.midolman.state.{MockNatBlockAllocator, NatBlockAllocator}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.util.collection.IPv4InvalidationArray
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.functors.{makePredicate, Predicate}

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

    protected override def bindZebraSelectLoop(): Unit = {
        bind(classOf[SelectLoopService]).toInstance(new SelectLoopService {
           override def doStart(): Unit = notifyStarted()
           override def doStop(): Unit = notifyStopped()
        })
        expose(classOf[SelectLoopService])
    }

    protected override def bindAllocator() {
        bind(classOf[NatBlockAllocator]).toInstance(new MockNatBlockAllocator)
        expose(classOf[NatBlockAllocator])
    }

    protected override def bindVirtualTopology() {
        val threadId = Thread.currentThread().getId
        bind(classOf[ExecutorService])
            .annotatedWith(Names.named(VirtualTopology.VtExecutorName))
            .toInstance(new SameThreadButAfterExecutorService())
        bind(classOf[Predicate])
            .annotatedWith(Names.named(VirtualTopology.VtExecutorCheckerName))
            .toInstance(makePredicate(
                            { threadId == Thread.currentThread().getId }))
        bind(classOf[ExecutorService])
            .annotatedWith(Names.named(VirtualTopology.IoExecutorName))
            .toInstance(new SameThreadButAfterExecutorService())

        bind(classOf[VirtualTopology])
            .asEagerSingleton()
        expose(classOf[VirtualTopology])
    }

}

