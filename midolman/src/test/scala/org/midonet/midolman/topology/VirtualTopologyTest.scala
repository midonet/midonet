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

import scala.concurrent.Await.{ready, result}
import scala.concurrent.duration._
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Notification

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.midolman.NotYetException
import org.midonet.midolman.topology.devices.{Port => SimulationPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class VirtualTopologyTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private implicit var store: Storage = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
    }

    feature("The topology returns a port with tryGet()") {
        scenario("The port does not exist") {
            Given("A random port identifier")
            val id = UUID.randomUUID

            When("Requesting the port")
            val e1 = intercept[NotYetException] {
                VirtualTopology.tryGet[SimulationPort](id)
            }

            Then("The request throws a NotYetException with a future")
            val future = e1.waitFor.asInstanceOf[Future[SimulationPort]]

            When("Waiting for the future to complete")
            val e2 = intercept[NotFoundException] {
                result(future, 1.seconds)
            }

            Then("The future fails with a NotFoundException")
            e2.clazz shouldBe classOf[TopologyPort]
            e2.id shouldBe id
        }

        scenario("The port exists in the topology and is not cached") {
            Given("A network port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id)
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            When("Requesting the port")
            // The port is not cached, the VT throws a not yet exception
            val e = intercept[NotYetException] {
                VirtualTopology.tryGet[SimulationPort](id)
            }

            Then("The request throws a NotYetException with a future")
            val future = e.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, 1.second)

            And("The future completes successfully with the given port")
            future.isCompleted shouldBe true
            future.value should not be None
            future.value.get.get.id shouldBe id
        }

        scenario("The port exists in the topology and is cached") {
            Given("A network port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id)
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("Requesting the port to update the VT cache")
            // Try get the port, and wait for the port to be cached.
            ready(intercept[NotYetException] {
                VirtualTopology.tryGet[SimulationPort](id)
            }.waitFor, 1.second)

            When("Requesting the port a second time")
            // Get the port again, this time from cache.
            val device = VirtualTopology.tryGet[SimulationPort](id)

            Then("The request should return the port")
            device should not be null
            device.id shouldBe id
        }

        scenario("The VT returns the latest port version") {
            Given("A network port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, tunnelKey = 1)
            store.create(port1)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            When("Requesting the port")
            // Try get the port, and wait for the port to be cached.
            val future = intercept[NotYetException] {
                VirtualTopology.tryGet[SimulationPort](id)
            }.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, 1.second)

            Then("The thrown future completes successfully with the port")
            future.isCompleted shouldBe true
            future.value should not be null
            future.value.get.get.id shouldBe id
            future.value.get.get.tunnelKey shouldBe 1

            When("The port is updated")
            val port2 = createBridgePort(id = id, tunnelKey = 2)
            store.update(port2)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("Requesting the port")
            val device = VirtualTopology.tryGet[SimulationPort](id)

            Then("The request should return the updated port")
            device should not be null
            device.id shouldBe id
            device.tunnelKey shouldBe 2
        }

        scenario("The VT removes the port from cache on port deletion") {
            Given("A network port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id)
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("Requesting the port to update the VT cache")
            ready(intercept[NotYetException] {
                VirtualTopology.tryGet[SimulationPort](id)
            }.waitFor, 1.second)

            When("Deleting the port")
            store.delete(classOf[TopologyPort], id)

            And("Requesting the port a second time")
            val e1 = intercept[NotYetException] {
                VirtualTopology.tryGet[SimulationPort](id)
            }

            Then("The request throws a NotYetException with a future")
            val future = e1.waitFor.asInstanceOf[Future[SimulationPort]]

            When("Waiting for the future to complete")
            val e2 = intercept[NotFoundException] {
                result(future, 1.seconds)
            }

            Then("The future fails with a NotFoundException")
            e2.clazz shouldBe classOf[TopologyPort]
            e2.id shouldBe id
        }
    }

    feature("The topology allows subscription for port updates") {
        scenario("The port does not exist") {
            Given("A random port identifier")
            val id = UUID.randomUUID

            And("An awaitable observer")
            val observer = new AwaitableObserver[SimulationPort]()

            When("Subscribing to the port")
            VirtualTopology.observable[SimulationPort](id).subscribe(observer)

            Then("The observer should receive an error")
            observer.await(1.second)
            observer.getOnErrorEvents.size shouldBe 1

            And("The exception shouldBe a NotFoundException")
            val e = observer.getOnErrorEvents.get(0)
            e.isInstanceOf[NotFoundException] shouldBe true
            e.asInstanceOf[NotFoundException].clazz shouldBe classOf[TopologyPort]
            e.asInstanceOf[NotFoundException].id shouldBe id
        }

        scenario("The port exists in the topology and is not cached") {
            Given("A network port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id)
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("An awaitable observer")
            val observer = new AwaitableObserver[SimulationPort]()

            When("Subscribing to the port")
            VirtualTopology.observable[SimulationPort](id).subscribe(observer)

            Then("The observer should received one onNext notification")
            observer.await(1.second)
            observer.getOnNextEvents should contain only VirtualTopology
                .get(id).value.get.get

            And("The simulation device should have the same identifier")
            observer.getOnNextEvents.get(0).id shouldBe id
        }

        scenario("The port exists in the topology and is cached") {
            Given("A network port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id)
            store.create(port)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("An awaitable observer")
            val observer = new AwaitableObserver[SimulationPort]()

            When("Requesting the port")
            val future = intercept[NotYetException] {
                VirtualTopology.tryGet[SimulationPort](id)
            }.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, 1.second)

            Then("The request should return the device")
            val device = future.value.get.get
            device.id shouldBe id

            When("Subscribing to the port")
            VirtualTopology.observable[SimulationPort](id).subscribe(observer)

            Then("The observer should received one onNext notification")
            observer.await(1.second)
            observer.getOnNextEvents should contain only device
        }

        scenario("The observer receives update notifications") {
            Given("A network port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, tunnelKey = 1)
            store.create(port1)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("An awaitable observer")
            val observer = new AwaitableObserver[SimulationPort]()

            When("Subscribing to the port")
            VirtualTopology.observable[SimulationPort](id).subscribe(observer)

            Then("The observer should receive one onNext notification")
            observer.await(1.second, 1)
            val device1 = VirtualTopology.get[SimulationPort](id).value.get.get
            observer.getOnNextEvents should contain only device1

            When("The port is updated")
            val port2 = createBridgePort(id = id, tunnelKey = 2)
            store.update(port2)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            Then("The observer should receive a second onNext notification")
            observer.await(1.second)
            val device2 = VirtualTopology.get[SimulationPort](id).value.get.get
            observer.getOnNextEvents should contain inOrder (device1, device2)
        }

        scenario("The observer should not receive updated after unsubscribe") {
            Given("A network port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, tunnelKey = 1)
            store.create(port1)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("An awaitable observer")
            val observer = new AwaitableObserver[SimulationPort]()

            When("Subscribing to the port")
            val subscription = VirtualTopology
                .observable[SimulationPort](id)
                .subscribe(observer)

            Then("The observer should receive one onNext notification")
            observer.await(1.second)
            val device1 = VirtualTopology.get[SimulationPort](id).value.get.get
            observer.getOnNextEvents should contain only device1

            When("Unsubscribing")
            subscription.unsubscribe()

            And("The port is updated")
            val port2 = createBridgePort(id = id, tunnelKey = 2)
            store.update(port2)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            Then("The observer should not receive a second onNext notification")
            observer.getOnNextEvents should contain only device1
        }

        scenario("The observer unsubscribing should not affect other observers") {
            Given("A network port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, tunnelKey = 1)
            store.create(port1)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("Two awaitable observers")
            val observer1 = new AwaitableObserver[SimulationPort]()
            val observer2 = new AwaitableObserver[SimulationPort]()

            When("Subscribing to the port by observer 1")
            val subscription = VirtualTopology
                .observable[SimulationPort](id)
                .subscribe(observer1)

            Then("The observer 1 should receive one onNext notification")
            observer1.await(1.second)
            val device1 = VirtualTopology.get[SimulationPort](id).value.get.get
            observer1.getOnNextEvents should contain only device1

            When("Subscribing to the port by observer 2")
            VirtualTopology
                .observable[SimulationPort](id)
                .subscribe(observer2)

            Then("The observer 2 should receive one onNext notification")
            observer2.await(1.second, 1)
            observer2.getOnNextEvents should contain only device1

            When("Observer 1 is unsubscribing")
            subscription.unsubscribe()

            And("The port is updated")
            val port2 = createBridgePort(id = id, tunnelKey = 2)
            store.update(port2)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            Then("The observer 1 should not receive a second onNext notification")
            observer1.getOnNextEvents should contain only device1

            Then("The observer 2 should receive a second onNext notification")
            observer2.await(1.second)
            val device2 = VirtualTopology.get[SimulationPort](id).value.get.get
            observer2.getOnNextEvents should contain inOrder(device1, device2)
        }

        scenario("The observer should receive a deletion notification") {
            Given("A network port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, tunnelKey = 1)
            store.create(port1)
            ready(store.get(classOf[TopologyPort], id), 1.second)

            And("An awaitable observer")
            val observer = new AwaitableObserver[SimulationPort]()

            When("Subscribing to the port")
            val subscription = VirtualTopology
                .observable[SimulationPort](id)
                .subscribe(observer)

            Then("The observer should receive one onNext notification")
            observer.await(1.second)
            val device1 = VirtualTopology.get[SimulationPort](id).value.get.get
            observer.getOnNextEvents should contain only device1

            When("The port is deleted")
            store.delete(classOf[TopologyPort], id)

            Then("The observer should receive a second onCompleted notification")
            observer.getOnNextEvents should contain only device1
            observer.getOnCompletedEvents should contain only Notification
                .createOnCompleted()
        }
    }
}
