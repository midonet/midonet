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
package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.cluster.state.StateStorage
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.{Bridge => SimulationBridge}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex.{OnCompleted, OnNext}
import org.midonet.util.reactivex.observers.AwaitableObserver
import org.midonet.cluster.models.Topology.{Network => TopologyBridge, Port}

import mockit.Mocked

@RunWith(classOf[JUnitRunner])
class BridgeMapperTest extends MidolmanSpec {

    private var store: Storage = _
    @Mocked
    var config: MidolmanConfig = _
    @Mocked
    var state: StateStorage = _
    implicit var vt: VirtualTopology = _

    override def beforeTest(): Unit = {
        store = new InMemoryStorage(new CallingThreadReactor)
        vt = new VirtualTopology(config, store, state, actorsService)

        store.registerClass(classOf[TopologyBridge])
        store.registerClass(classOf[Port])

        store.declareBinding(classOf[Port], "network_id", CLEAR,
                             classOf[TopologyBridge], "port_ids", ERROR)

        store.build()
    }

    feature("[WIP] Test bridge updates") {
        scenario("Bridge created") {
            val id = UUID.randomUUID
            val bridge = TopologyBridge.newBuilder()
                .setId(id.asProto)
                .build()

            val obs = new AwaitableObserver[SimulationBridge]()

            store.create(bridge)
            VirtualTopology.observable[SimulationBridge](id).subscribe(obs)

            obs.await(1.second) should be (true)
            obs.notifications.size should be (1)
            obs.notifications(0) match {
                case OnNext(device: SimulationBridge) =>
                    device.id should be (id)
                case _ => fail("Unknown notification")
            }
        }

        scenario("Bridge updated") {
            val id = UUID.randomUUID
            val bridge = TopologyBridge.newBuilder()
                .setId(id.asProto)
                .build()

            val obs = new AwaitableObserver[SimulationBridge](2)

            store.create(bridge)
            VirtualTopology.observable[SimulationBridge](id).subscribe(obs)
            store.update(bridge)

            obs.await(1.second) should be (true)
            obs.notifications.size should be (2)
            obs.notifications foreach {
                case OnNext(device: SimulationBridge) =>
                    device.id should be (id)
                case _ => fail("Unknown notification")
            }
        }

        scenario("Bridge deleted") {
            val id = UUID.randomUUID
            val bridge = TopologyBridge.newBuilder()
                .setId(id.asProto)
                .build()

            val obs = new AwaitableObserver[SimulationBridge](2)

            store.create(bridge)
            VirtualTopology.observable[SimulationBridge](id).subscribe(obs)
            store.delete(classOf[TopologyBridge], id)

            obs.await(1.second) should be (true)
            obs.notifications.size should be (2)
            obs.notifications(0) match {
                case OnNext(device: SimulationBridge) =>
                    device.id should be (id)
                case _ => fail("Unknown notification")
            }
            obs.notifications(1) should be (OnCompleted())
        }
    }

    feature("[WIP] Test port updates") {
        scenario("Port created as exterior port") {
        }

        scenario("Port created as not exterior port") {
        }

        scenario("Port updated from exterior to interior") {
        }

        scenario("Port updated from interior to exterior") {
        }

        scenario("Exterior port deleted") {
        }
    }

}
