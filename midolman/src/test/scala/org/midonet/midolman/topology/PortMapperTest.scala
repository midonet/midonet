/*
 * Copyright 2014-2015 Midokura SARL
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

import scala.concurrent.Await._
import scala.concurrent.duration._

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, StorageWithOwnership}
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.midolman.topology.devices.{Port => SimPort, VxLanPort, RouterPort, BridgePort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class PortMapperTest extends MidolmanSpec with TopologyBuilder
                     with TopologyMatchers {

    private var vt: VirtualTopology = _
    private implicit var store: StorageWithOwnership = _
    private var mapper: PortMapper = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[StorageWithOwnership])
    }

    protected override def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config
    }

    feature("The port mapper emits port devices") {
        scenario("The mapper emits error for non-existing port") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A port mapper")
            val mapper = new PortMapper(id, vt)

            And("An observer to the port mapper")
            val obs = new AwaitableObserver[SimPort]

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.await(1 second)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyPort]
            e.id shouldBe id
        }

        scenario("The mapper emits existing bridge port") {
            Given("A port mapper")
            val id = UUID.randomUUID
            val mapper = new PortMapper(id, vt)

            And("A bridge port")
            val port = createBridgePort(id = id)

            And("An observer to the port mapper")
            val obs = new AwaitableObserver[SimPort]

            When("The port is created")
            store.create(port)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a bridge port")
            obs.await(1 second)
            val device = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device shouldBeDeviceOf port
            device.active shouldBe false
        }

        scenario("The mapper emits existing router port") {
            Given("A port mapper")
            val id = UUID.randomUUID
            val mapper = new PortMapper(id, vt)

            And("A router port")
            val port = createRouterPort(id = id)

            And("An observer to the port mapper")
            val obs = new AwaitableObserver[SimPort]

            When("The port is created")
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1 second)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a router port")
            obs.await(1 second)
            val device = obs.getOnNextEvents.get(0).asInstanceOf[RouterPort]
            device shouldBeDeviceOf port
            device.active shouldBe false
        }

        scenario("The mapper emits existing VXLAN port") {
            Given("A port mapper")
            val id = UUID.randomUUID
            val mapper = new PortMapper(id, vt)

            And("A VXLAN port")
            val port = createVxLanPort(id = id)

            And("An observer to the port mapper")
            val obs = new AwaitableObserver[SimPort]

            When("The port is created")
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1 second)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a router port")
            obs.await(1 second)
            val device = obs.getOnNextEvents.get(0).asInstanceOf[VxLanPort]
            device shouldBeDeviceOf port
            device.active shouldBe false
        }

        scenario("The mapper emits new device on port update") {
            Given("A port mapper")
            val id = UUID.randomUUID
            val mapper = new PortMapper(id, vt)

            And("A port")
            val port1 = createBridgePort(id = id, adminStateUp = false)
            val port2 = createBridgePort(id = id, adminStateUp = true)

            And("An observer to the port mapper")
            val obs = new AwaitableObserver[SimPort]

            When("The port is created")
            store.create(port1)
            ready(store.get(classOf[TopologyPort], id), 1 second)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.await(1 second, 1)
            val device1 = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device1 shouldBeDeviceOf port1
            device1.adminStateUp shouldBe false
            device1.active shouldBe false

            When("The port is updated")
            store.update(port2)
            ready(store.get(classOf[TopologyPort], id), 1 second)

            Then("The observer should receive the update")
            obs.await(1 second)
            val device2 = obs.getOnNextEvents.get(1).asInstanceOf[BridgePort]
            device2 shouldBeDeviceOf port2
            device2.adminStateUp shouldBe true
            device2.active shouldBe false
        }

        scenario("The mapper emits new device on port owner update") {
            Given("A port mapper")
            val id = UUID.randomUUID
            val mapper = new PortMapper(id, vt)

            And("A port")
            val port = createBridgePort(id = id)

            And("An observer to the port mapper")
            val obs = new AwaitableObserver[SimPort]

            When("The port is created")
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1 second)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.await(1 second, 1)
            obs.getOnNextEvents.size shouldBe 1
            val device1 = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device1 shouldBeDeviceOf port
            device1.active shouldBe false

            When("Adding a first owner to the port")
            val owner1 = UUID.randomUUID
            store.updateOwner(classOf[TopologyPort], id, owner1, true)
            result(store.getOwners(classOf[TopologyPort], id),
                  1 second) shouldBe Set(owner1.toString)

            Then("The observer should receive the update")
            obs.await(1 second, 1)
            obs.getOnNextEvents.size shouldBe 2
            val device2 = obs.getOnNextEvents.get(1).asInstanceOf[BridgePort]
            device2 shouldBeDeviceOf port
            device2.active shouldBe true

            When("Adding a second owner to the port")
            val owner2 = UUID.randomUUID
            store.updateOwner(classOf[TopologyPort], id, owner2, true)
            result(store.getOwners(classOf[TopologyPort], id),
                1 second) shouldBe Set(owner1.toString, owner2.toString)

            Then("The observer should not receive a new update")
            obs.getOnNextEvents.size shouldBe 2

            When("Removing the first owner from the port")
            store.deleteOwner(classOf[TopologyPort], id, owner1)
            result(store.getOwners(classOf[TopologyPort], id),
                1 second) shouldBe Set(owner2.toString)

            Then("The observer should not receive a new update")
            obs.getOnNextEvents.size shouldBe 2

            When("Removing the second owner from the port")
            store.deleteOwner(classOf[TopologyPort], id, owner2)
            result(store.getOwners(classOf[TopologyPort], id),
                1 second) shouldBe Set.empty

            Then("The observer should receive a new update")
            obs.getOnNextEvents.size shouldBe 3
            val device3 = obs.getOnNextEvents.get(2).asInstanceOf[BridgePort]
            device3 shouldBeDeviceOf port
            device3.active shouldBe false
        }

        scenario("The mapper completes on port delete") {
            Given("A port mapper")
            val id = UUID.randomUUID
            val mapper = new PortMapper(id, vt)

            And("A port")
            val port = createBridgePort(id = id)

            And("An observer to the port mapper")
            val obs = new AwaitableObserver[SimPort]

            When("The port is created")
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1 second)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.await(1 second, 1)
            val device1 = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device1 shouldBeDeviceOf port

            When("The port is deleted")
            store.delete(classOf[TopologyPort], id)
            ready(store.get(classOf[TopologyPort], id), 1 second)

            Then("The observer should receive a completed notification")
            obs.await(1 second)
            obs.getOnCompletedEvents should not be empty
        }
    }
}
