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
import scala.concurrent.Future
import scala.concurrent.duration._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.{Notification, Observable}
import org.midonet.cluster.data.storage.{CreateOp, NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Router, Port => TopologyPort}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.topology.VirtualTopology.Key
import org.midonet.midolman.simulation.{Port => SimulationPort}
import org.midonet.midolman.topology.devices.{BgpPort, BgpRouter}
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class VirtualTopologyTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

    private val bridgeId = UUID.randomUUID
    private val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        store.create(createBridge(id = bridgeId))
    }

    feature("The topology returns a port with tryGet()") {
        scenario("The port does not exist") {
            Given("A random port identifier")
            val id = UUID.randomUUID

            When("Requesting the port")
            val e1 = intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }

            Then("The request throws a NotYetException with a future")
            val future = e1.waitFor.asInstanceOf[Future[SimulationPort]]

            When("Waiting for the future to complete")
            val e2 = intercept[NotFoundException] {
                result(future, timeout)
            }

            Then("The future fails with a NotFoundException")
            e2.clazz shouldBe classOf[TopologyPort]
            e2.id shouldBe id
        }

        scenario("The port exists in the topology and is not cached") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id, bridgeId = Some(bridgeId))
            store.create(port)

            When("Requesting the port")
            // The port is not cached, the VT throws a not yet exception
            val e = intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }

            Then("The request throws a NotYetException with a future")
            val future = e.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, timeout)

            And("The future completes successfully with the given port")
            future.isCompleted shouldBe true
            future.value should not be None
            future.value.get.get.id shouldBe id
        }

        scenario("The port exists in the topology and is cached") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id, bridgeId = Some(bridgeId))
            store.create(port)

            And("Requesting the port to update the VT cache")
            // Try get the port, and wait for the port to be cached.
            ready(intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }.waitFor, timeout)

            And("Creating an observer to the VT observable")
            val observer = new DeviceObserver[SimulationPort](vt)
            vt.observables.get(Key(classOf[SimulationPort], id))
                .asInstanceOf[Observable[SimulationPort]]
                .subscribe(observer)

            And("Waiting for the notification")
            observer.awaitOnNext(1, timeout) shouldBe true

            When("Requesting the port a second time")
            // Get the port again, this time from cache.
            val device = VirtualTopology.tryGet(classOf[SimulationPort], id)

            Then("The request should return the port")
            device should not be null
            device.id shouldBe id
        }

        scenario("The VT returns the latest port version") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            store.create(port1)

            When("Requesting the port")
            // Try get the port, and wait for the port to be cached.
            val future = intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, timeout)

            Then("The thrown future completes successfully with the port")
            future.isCompleted shouldBe true
            future.value should not be null
            future.value.get.get.id shouldBe id
            future.value.get.get.tunnelKey shouldBe 1

            When("Creating an observer to the VT observable")
            val observer = new DeviceObserver[SimulationPort](vt)
            vt.observables.get(Key(classOf[SimulationPort], id))
                .asInstanceOf[Observable[SimulationPort]]
                .subscribe(observer)

            And("The port is updated")
            val port2 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            store.update(port2)

            And("Waiting for the notifications")
            observer.awaitOnNext(2, timeout) shouldBe true

            And("Requesting the port")
            val device = VirtualTopology.tryGet(classOf[SimulationPort], id)

            Then("The request should return the updated port")
            device should not be null
            device.id shouldBe id
            device.tunnelKey shouldBe 2
        }

        scenario("The VT removes the port from cache on port deletion") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id, bridgeId = Some(bridgeId))
            store.create(port)

            And("Requesting the port to update the VT cache")
            ready(intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }.waitFor, timeout)

            When("Creating an observer to the VT observable")
            val observer = new DeviceObserver[SimulationPort](vt)
            vt.observables.get(Key(classOf[SimulationPort], id))
                .asInstanceOf[Observable[SimulationPort]]
                .subscribe(observer)
            observer.awaitOnNext(1, timeout)

            And("Deleting the port")
            store.delete(classOf[TopologyPort], id)

            And("Waiting for the notifications")
            observer.awaitCompletion(timeout)

            And("Requesting the port a second time")
            val e1 = intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }

            Then("The request throws a NotYetException with a future")
            val future = e1.waitFor.asInstanceOf[Future[SimulationPort]]

            When("Waiting for the future to complete")
            val e2 = intercept[NotFoundException] {
                result(future, timeout)
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
            val observer = new DeviceObserver[SimulationPort](vt)

            When("Subscribing to the port")
            VirtualTopology.observable(classOf[SimulationPort], id)
                           .subscribe(observer)

            Then("The observer should receive an error")
            observer.awaitCompletion(timeout)
            observer.getOnErrorEvents.size shouldBe 1

            And("The exception shouldBe a NotFoundException")
            val e = observer.getOnErrorEvents.get(0)
            e.isInstanceOf[NotFoundException] shouldBe true
            e.asInstanceOf[NotFoundException].clazz shouldBe classOf[TopologyPort]
            e.asInstanceOf[NotFoundException].id shouldBe id
        }

        scenario("The port exists in the topology and is not cached") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id, bridgeId = Some(bridgeId))
            store.create(port)

            And("An awaitable observer")
            val observer = new DeviceObserver[SimulationPort](vt)

            When("Subscribing to the port")
            VirtualTopology.observable(classOf[SimulationPort], id)
                           .subscribe(observer)

            Then("The observer should received one onNext notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should contain only VirtualTopology
                .get(classOf[SimulationPort], id).value.get.get

            And("The simulation device should have the same identifier")
            observer.getOnNextEvents.get(0).id shouldBe id
        }

        scenario("The port exists in the topology and is cached") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id, bridgeId = Some(bridgeId))
            store.create(port)

            And("An awaitable observer")
            val observer = new DeviceObserver[SimulationPort](vt)

            When("Requesting the port")
            val future = intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, timeout)

            Then("The request should return the device")
            val device = future.value.get.get
            device.id shouldBe id

            When("Subscribing to the port")
            VirtualTopology.observable(classOf[SimulationPort], id)
                           .subscribe(observer)

            Then("The observer should received one onNext notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should contain only device
        }

        scenario("The observer receives update notifications") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            store.create(port1)

            And("An awaitable observer")
            val observer = new DeviceObserver[SimulationPort](vt)

            When("Subscribing to the port")
            VirtualTopology.observable(classOf[SimulationPort], id)
                           .subscribe(observer)

            Then("The observer should receive one onNext notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            val device1 = VirtualTopology.get(classOf[SimulationPort], id).value.get.get
            observer.getOnNextEvents should contain only device1

            When("The port is updated")
            val port2 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            store.update(port2)

            Then("The observer should receive a second onNext notification")
            observer.awaitOnNext(2, timeout) shouldBe true
            val device2 = VirtualTopology.get(classOf[SimulationPort], id).value.get.get
            observer.getOnNextEvents should contain inOrder (device1, device2)
        }

        scenario("The observer should not receive updated after unsubscribe") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            store.create(port1)

            And("An awaitable observer")
            val observer = new DeviceObserver[SimulationPort](vt)

            When("Subscribing to the port")
            val subscription = VirtualTopology
                .observable(classOf[SimulationPort], id)
                .subscribe(observer)

            Then("The observer should receive one onNext notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            val device1 = VirtualTopology.get(classOf[SimulationPort], id).value.get.get
            observer.getOnNextEvents should contain only device1

            When("Unsubscribing")
            subscription.unsubscribe()

            And("The port is updated")
            val port2 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            store.update(port2)

            Then("The observer should not receive a second onNext notification")
            observer.getOnNextEvents should contain only device1
        }

        scenario("The observer unsubscribing should not affect other observers") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            store.create(port1)

            And("Two awaitable observers")
            val observer1 = new DeviceObserver[SimulationPort](vt)
            val observer2 = new DeviceObserver[SimulationPort](vt)

            When("Subscribing to the port by observer 1")
            val subscription = VirtualTopology
                .observable(classOf[SimulationPort], id)
                .subscribe(observer1)

            Then("The observer 1 should receive one onNext notification")
            observer1.awaitOnNext(1, timeout) shouldBe true
            val device1 = VirtualTopology.get(classOf[SimulationPort], id).value.get.get
            observer1.getOnNextEvents should contain only device1

            When("Subscribing to the port by observer 2")
            VirtualTopology
                .observable(classOf[SimulationPort], id)
                .subscribe(observer2)

            Then("The observer 2 should receive one onNext notification")
            observer2.awaitOnNext(1, timeout) shouldBe true
            observer2.getOnNextEvents should contain only device1

            When("Observer 1 is unsubscribing")
            subscription.unsubscribe()

            And("The port is updated")
            val port2 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            store.update(port2)

            Then("The observer 1 should not receive a second onNext notification")
            observer1.getOnNextEvents should contain only device1

            Then("The observer 2 should receive a second onNext notification")
            observer2.awaitOnNext(2, timeout) shouldBe true
            val device2 = VirtualTopology.get(classOf[SimulationPort], id).value.get.get
            observer2.getOnNextEvents should contain inOrder(device1, device2)
        }

        scenario("The observer should receive a deletion notification") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port1 = createBridgePort(id = id, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            store.create(port1)

            And("An awaitable observer")
            val observer = new DeviceObserver[SimulationPort](vt)

            When("Subscribing to the port")
            VirtualTopology
                .observable(classOf[SimulationPort], id)
                .subscribe(observer)

            Then("The observer should receive one onNext notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            val device1 = VirtualTopology.get(classOf[SimulationPort], id).value.get.get
            observer.getOnNextEvents should contain only device1

            When("The port is deleted")
            store.delete(classOf[TopologyPort], id)
            observer.awaitCompletion(timeout)

            Then("The observer should receive a second onCompleted notification")
            observer.getOnNextEvents should contain only device1
            observer.getOnCompletedEvents should contain only Notification
                .createOnCompleted()
        }
    }

    feature("Test metrics") {
        scenario("Add a port, then remove it") {
            Given("A bridge port")
            val id = UUID.randomUUID
            val port = createBridgePort(id = id, bridgeId = Some(bridgeId))
            store.create(port)

            When("Requesting the port to update the VT cache")
            ready(intercept[NotYetException] {
                VirtualTopology.tryGet(classOf[SimulationPort], id)
            }.waitFor, timeout)

            Then("The metrics should be updated")
            vt.metrics.devicesGauge.getValue shouldBe 2
            vt.metrics.observablesGauge.getValue shouldBe 2
            vt.metrics.cacheHitGauge.getValue shouldBe 0
            vt.metrics.cacheMissGauge.getValue shouldBe 1
            vt.metrics.deviceUpdateCounter.getCount shouldBe 2
            vt.metrics.deviceUpdateMeter.getCount shouldBe 2
            vt.metrics.deviceLatencyHistogram.getCount shouldBe 2

            When("Creating an observer to the VT observable")
            val observer = new DeviceObserver[SimulationPort](vt)
            vt.observables.get(Key(classOf[SimulationPort], id))
                .asInstanceOf[Observable[SimulationPort]]
                .subscribe(observer)
            observer.awaitOnNext(1, timeout)

            And("Updating the device")
            store.update(port.toBuilder.setTunnelKey(1L).build())
            observer.awaitOnNext(2, timeout)

            Then("The metrics should be updated")
            /* UpdateCounter, UpdateMeter, Gauges, and LatencyHistogram should
             * all be increased by one because of the QosService subscribing
             * to the host mapper on startup. */
            vt.metrics.devicesGauge.getValue shouldBe 2
            vt.metrics.observablesGauge.getValue shouldBe 2
            vt.metrics.cacheHitGauge.getValue shouldBe 0
            vt.metrics.cacheMissGauge.getValue shouldBe 1
            vt.metrics.deviceUpdateCounter.getCount shouldBe 3
            vt.metrics.deviceUpdateMeter.getCount shouldBe 3
            vt.metrics.deviceLatencyHistogram.getCount shouldBe 2

            When("Requesting the port")
            VirtualTopology.tryGet(classOf[SimulationPort], id)

            Then("The metrics should be updated")
            vt.metrics.cacheHitGauge.getValue shouldBe 1

            And("Deleting the port")
            store.delete(classOf[TopologyPort], id)

            And("Waiting for the notifications")
            observer.awaitCompletion(timeout)

            Then("The metrics should be updated")
            /* UpdateCounter, UpdateMeter, Gauges, and LatencyHistogram should
             * all be increased by one because of the QosService subscribing
             * to the host mapper on startup. */
            vt.metrics.devicesGauge.getValue shouldBe 1
            vt.metrics.observablesGauge.getValue shouldBe 1
            vt.metrics.deviceUpdateCounter.getCount shouldBe 3
            vt.metrics.deviceUpdateMeter.getCount shouldBe 3
            vt.metrics.deviceCompleteCounter.getCount shouldBe 1
            vt.metrics.deviceCompleteMeter.getCount shouldBe 1
            vt.metrics.deviceLatencyHistogram.getCount shouldBe 2
            vt.metrics.deviceLifetimeHistogram.getCount shouldBe 1
      }
    }

    feature("Topology clears devices and observables") {
        scenario("BGP router on delete") {
            Given("A router")
            val router = createRouter()
            store.create(router)

            And("A BGP router observer")
            val observer = new DeviceObserver[BgpRouter](vt)

            When("The observer subscribes")
            VirtualTopology.observable(classOf[BgpRouter], router.getId)
                           .subscribe(observer)

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents should have size 1

            And("The topology cache should contain an observable")
            vt.observables.containsKey(Key(classOf[BgpRouter], router.getId)) shouldBe true

            When("The router is deleted")
            store.delete(classOf[Router], router.getId)

            Then("The observable should complete")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1

            And("The topology cache should not contain the observable")
            vt.observables.containsKey(Key(classOf[BgpRouter], router.getId)) shouldBe false
        }

        scenario("BGP router on error") {
            Given("A random router identifier")
            val routerId = UUID.randomUUID()

            And("A BGP router observer")
            val observer = new DeviceObserver[BgpRouter](vt)

            When("The observer subscribes")
            VirtualTopology.observable(classOf[BgpRouter], routerId)
                           .subscribe(observer)

            Then("The observer should receive the device")
            observer.awaitCompletion(timeout)
            observer.getOnErrorEvents should have size 1

            And("The topology cache should not contain an observable")
            vt.observables.containsKey(Key(classOf[BgpRouter], routerId)) shouldBe false
        }

        scenario("BGP port on non-router ports") {
            Given("A bridge port")
            val port = createBridgePort(bridgeId = Some(bridgeId))
            store.create(port)

            And("A BGP port observer")
            val observer = new DeviceObserver[BgpPort](vt)

            When("The observer subscribes")
            VirtualTopology.observable(classOf[BgpPort], port.getId)
                           .subscribe(observer)

            Then("The observable should complete")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1

            And("The topology cache should not contain the observable")
            vt.observables.containsKey(Key(classOf[BgpPort], port.getId)) shouldBe false
        }

        scenario("BGP port on error") {
            Given("A router port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP port observer")
            val observer = new DeviceObserver[BgpPort](vt)

            When("The observer subscribes")
            VirtualTopology.observable(classOf[BgpPort], port.getId)
                .subscribe(observer)

            Then("The observer should receive the device")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents should have size 1

            And("The topology cache should contain an observable")
            vt.observables.containsKey(Key(classOf[BgpPort], port.getId)) shouldBe true

            When("The port is deleted")
            store.delete(classOf[TopologyPort], port.getId)

            Then("The observable should error with BgpPortDeleted")
            observer.awaitCompletion(timeout)
            observer.getOnErrorEvents should have size 1

            And("The topology cache should not contain the observable")
            vt.observables.containsKey(Key(classOf[BgpPort], port.getId)) shouldBe false
        }
    }
}
