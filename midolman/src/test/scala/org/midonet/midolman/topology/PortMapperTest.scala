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

import scala.collection.mutable
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.ActiveKey
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.topology.TopologyBuilder._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.simulation.{BridgePort, Port => SimPort, RouterPort, VxLanPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class PortMapperTest extends MidolmanSpec with TopologyBuilder
                     with TopologyMatchers {

    private var vt: VirtualTopology = _
    private var store: InMemoryStorage = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
                        .asInstanceOf[InMemoryStorage]
    }

    feature("The port mapper emits port devices") {
        scenario("The mapper emits error for non-existing port") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyPort]
            e.id shouldBe id
        }

        scenario("The mapper emits existing bridge port") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A bridge and a bridge port")
            val bridge = createBridge()
            val port = createBridgePort(id = id,
                                        bridgeId = Some(bridge.getId.asJava))
            store.multi(Seq(CreateOp(bridge), CreateOp(port)))

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a bridge port")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device shouldBeDeviceOf port
            device.isActive shouldBe false
        }

        scenario("The mapper emits existing router port") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A router and router port")
            val router = createRouter()
            val port = createRouterPort(id = id,
                                        routerId = Some(router.getId.asJava))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a router port")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0).asInstanceOf[RouterPort]
            device shouldBeDeviceOf port
            device.isActive shouldBe false
        }

        scenario("The mapper emits existing VXLAN port") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A VXLAN port")
            val port = createVxLanPort(id = id, vtepId = Some(UUID.randomUUID()))
            store.create(port)

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a router port")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0).asInstanceOf[VxLanPort]
            device shouldBeDeviceOf port
            device.isActive shouldBe true
        }

        scenario("The mapper emits new device on port update") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A bridge and two ports")
            val bridge = createBridge()
            val port1 = createBridgePort(id = id, adminStateUp = false,
                                         bridgeId = Some(bridge.getId.asJava))
            val port2 = createBridgePort(id = id, adminStateUp = true,
                                         bridgeId = Some(bridge.getId.asJava))
            store.multi(Seq(CreateOp(bridge), CreateOp(port1)))

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device1 shouldBeDeviceOf port1
            device1.adminStateUp shouldBe false
            device1.isActive shouldBe false

            When("The port is updated")
            store.update(port2)

            Then("The observer should receive the update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1).asInstanceOf[BridgePort]
            device2 shouldBeDeviceOf port2
            device2.adminStateUp shouldBe true
            device2.isActive shouldBe false

            When("The port is updated again restoring the admin status")
            store.update(port1)

            Then("The observer should receive the update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device3 = obs.getOnNextEvents.get(2).asInstanceOf[BridgePort]
            device3 shouldBeDeviceOf port1
            device3.adminStateUp shouldBe false
            device3.isActive shouldBe false
        }

        scenario("The mapper emits new device on port owner update") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A bridge and a port")
            val bridge = createBridge()
            val port = createBridgePort(id = id,
                                        bridgeId = Some(bridge.getId.asJava),
                                        hostId = Some(InMemoryStorage.namespaceId))
            store.multi(Seq(CreateOp(bridge), CreateOp(port)))

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.size shouldBe 1
            val device1 = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device1 shouldBeDeviceOf port
            device1.isActive shouldBe false

            When("Adding a first owner to the port")
            val owner1 = UUID.randomUUID.toString
            store.addValue(classOf[TopologyPort], id, ActiveKey, owner1)
                 .await(timeout)

            Then("The observer should receive the update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.size shouldBe 2
            val device2 = obs.getOnNextEvents.get(1).asInstanceOf[BridgePort]
            device2 shouldBeDeviceOf port
            device2.isActive shouldBe true

            When("Removing the first owner from the port")
            store.removeValue(classOf[TopologyPort], id, ActiveKey, owner1)
                 .await(timeout)

            Then("The observer should receive a new update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.size shouldBe 3
            val device3 = obs.getOnNextEvents.get(2).asInstanceOf[BridgePort]
            device3 shouldBeDeviceOf port
            device3.isActive shouldBe false
        }

        scenario("The mapper handles port migration") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A second host")
            val host2 = createHost()
            val hostId2 = host2.getId.asJava.toString
            store create host2

            And("A bridge and a port bound to a first host")
            val bridge = createBridge()
            val port1 = createBridgePort(id = id,
                                         bridgeId = Some(bridge.getId.asJava),
                                         hostId = Some(InMemoryStorage.namespaceId))
            store.multi(Seq(CreateOp(bridge), CreateOp(port1)))

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port as not active")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device1 shouldBeDeviceOf port1
            device1.isActive shouldBe false

            When("The first host sets the port as active")
            store.addValue(classOf[TopologyPort], id, ActiveKey, store.namespace)
                .await(timeout)

            Then("The observer should receive the port as active")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1).asInstanceOf[BridgePort]
            device2 shouldBeDeviceOf port1
            device2.isActive shouldBe true

            When("The port migrates to the second host")
            val port2 = port1.setHostId(host2.getId)
            store update port2

            Then("The observer should receive the port as not active")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device3 = obs.getOnNextEvents.get(2).asInstanceOf[BridgePort]
            device3 shouldBeDeviceOf port2
            device3.isActive shouldBe false

            When("The first host sets the port as active")
            store.addValueAs(hostId2, classOf[TopologyPort], id, ActiveKey,
                             store.namespace)
                .await(timeout)

            Then("The observer should receive the port as active")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device4 = obs.getOnNextEvents.get(3).asInstanceOf[BridgePort]
            device4 shouldBeDeviceOf port2
            device4.isActive shouldBe true
        }

        scenario("The mapper completes on port delete") {
            Given("A port identifier")
            val id = UUID.randomUUID

            And("A bridge and a port")
            val bridge = createBridge()
            val port = createBridgePort(id = id,
                                        bridgeId = Some(bridge.getId.asJava))
            store.multi(Seq(CreateOp(bridge), CreateOp(port)))

            And("A port mapper")
            val mapper = new PortMapper(id, vt, mutable.Map())

            And("An observer to the port mapper")
            val obs = new DeviceObserver[SimPort](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0).asInstanceOf[BridgePort]
            device1 shouldBeDeviceOf port

            When("The port is deleted")
            store.delete(classOf[TopologyPort], id)

            Then("The observer should receive a completed notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }
    }
}
