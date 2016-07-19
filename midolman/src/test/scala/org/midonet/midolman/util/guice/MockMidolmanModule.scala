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

import scala.collection.IndexedSeq

import com.google.common.util.concurrent.MoreExecutors
import com.google.inject.name.Names

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import akka.actor.ActorSystem

import org.midonet.midolman.services.SelectLoopService
import org.midonet.midolman.{BackChannelHandler, SimulationBackChannel, ShardedSimulationBackChannel}
import org.midonet.midolman.{MockScheduler, PacketWorker, PacketWorkersService}
import org.midonet.midolman.cluster.MidolmanModule
import org.midonet.midolman.SimulationBackChannel.BackChannelMessage
import org.midonet.midolman.state.{MockNatBlockAllocator, NatBlockAllocator}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.util.collection.IPv4InvalidationArray
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.functors.{makePredicate, Predicate}

class MockMidolmanModule extends MidolmanModule {
    protected override def bindSimulationBackChannel(): Unit = {
        val backChannel = new ShardedSimulationBackChannel()
        bind(classOf[SimulationBackChannel])
            .toInstance(backChannel)
        expose(classOf[SimulationBackChannel])
        bind(classOf[ShardedSimulationBackChannel])
            .toInstance(backChannel)
        expose(classOf[ShardedSimulationBackChannel])

        IPv4InvalidationArray.reset()
    }

    protected override def bindZebraSelectLoop(): Unit = {
        bind(classOf[SelectLoopService]).toInstance(new SelectLoopService {
           override def doStart(): Unit = notifyStarted()
           override def doStop(): Unit = notifyStopped()
        })
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

    override def bindActorSystem() {
        val config = ConfigFactory.load()
            .getConfig("midolman")
            .withValue("akka.scheduler.implementation",
                       ConfigValueFactory.fromAnyRef(
                           classOf[MockScheduler].getName))
        val as = ActorSystem.create("MidolmanActors", config)
        bind(classOf[ActorSystem]).toInstance(as)
        expose(classOf[ActorSystem])
    }

    override def bindPacketWorkersService() {
        val packetWorkersService = new PacketWorkersService() {
            override def workers: IndexedSeq[PacketWorker] = IndexedSeq()
            override def doStart(): Unit = notifyStarted()
            override def doStop(): Unit = notifyStopped()
        }
        bind(classOf[PacketWorkersService])
            .toInstance(packetWorkersService)
        expose(classOf[PacketWorkersService])
    }
}
