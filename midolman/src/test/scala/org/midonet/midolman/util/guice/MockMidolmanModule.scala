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

import scala.collection.IndexedSeq

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import akka.actor.ActorSystem

import org.midonet.midolman.{BackChannelHandler, DatapathController, SimulationBackChannel, ShardedSimulationBackChannel}
import org.midonet.midolman.{MockScheduler, PacketWorker, PacketWorkersService}
import org.midonet.midolman.cluster.MidolmanModule
import org.midonet.midolman.state.{MockNatBlockAllocator, NatBlock, NatRange, NatBlockAllocator}
import org.midonet.util.functors.Callback

class MockMidolmanModule extends MidolmanModule {
    protected override def bindAllocator() {
        bind(classOf[NatBlockAllocator]).toInstance(new MockNatBlockAllocator)
        expose(classOf[NatBlockAllocator])
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
            def initWithDatapath(msg: DatapathController.DatapathReady): Unit = {}
            override def doStart(): Unit = notifyStarted()
            override def doStop(): Unit = notifyStopped()
        }
        bind(classOf[PacketWorkersService])
            .toInstance(packetWorkersService)
        expose(classOf[PacketWorkersService])
    }
}
