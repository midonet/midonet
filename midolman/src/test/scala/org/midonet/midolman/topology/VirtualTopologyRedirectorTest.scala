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

import akka.actor.Props
import akka.testkit.TestActorRef

import com.typesafe.config.{Config, ConfigValueFactory}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation._
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.devices.{Port => SimulationPort, PoolHealthMonitorMap, BridgePort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.{AwaitableActor, MessageAccumulator}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class VirtualTopologyRedirectorTest extends MidolmanSpec with TopologyBuilder
                                    with TopologyMatchers {

    private class TestableVTA extends VirtualTopologyActor
                              with MessageAccumulator
    private type SenderActor = AwaitableActor with MessageAccumulator

    private var backend: MidonetBackend = _
    private var vt: VirtualTopology = _
    private var vta: TestableVTA = _
    private implicit var senderRef: TestActorRef[SenderActor] = _
    private implicit var sender: SenderActor = _
    private val bridgeId = UUID.randomUUID
    private val timeout = 5 seconds

    registerActors(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def fillConfig(config: Config) = {
        super.fillConfig(config).withValue("zookeeper.use_new_stack",
                                           ConfigValueFactory.fromAnyRef(true))
    }

    override def beforeTest(): Unit = {
        backend = injector.getInstance(classOf[MidonetBackend])
        backend.isEnabled shouldBe true
        vt = injector.getInstance(classOf[VirtualTopology])
        vta = VirtualTopologyActor.as[TestableVTA]
        senderRef = TestActorRef(Props(new AwaitableActor
                                           with MessageAccumulator))(actorSystem)
        sender = senderRef.underlyingActor
        backend.store.create(createBridge(id = bridgeId))
    }

    private def expectLast(fn: PartialFunction[Any, Unit])
                          (implicit ma: MessageAccumulator)= {
        fn.applyOrElse[Any, Unit](ma.messages.last,
                                  _ => fail("Unexpected message"))
    }

    feature("Test tryAsk") {
        scenario("Port does not exist") {
            Given("A random port identifier")
            val portId = UUID.randomUUID

            When("Requesting the port")
            val e1 = intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[SimulationPort](portId)
            }

            Then("The request throws a NotYetException with a future")
            val future = e1.waitFor.asInstanceOf[Future[SimulationPort]]

            When("Waiting for the future to complete")
            val e2 = intercept[Exception] {
                result(future, timeout)
            }.getCause.asInstanceOf[NotFoundException]

            Then("The future fails with a NotFoundException")
            e2.clazz shouldBe classOf[TopologyPort]
            e2.id shouldBe portId
        }

        scenario("Port exists and is not cached") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            backend.store.create(port)

            When("Requesting the port")
            val e = intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[SimulationPort](portId)
            }

            Then("The request throws a NotYetException with a future")
            val future = e.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, timeout)

            And("The future completes successfully with the given port")
            future.isCompleted shouldBe true
            future.value should not be None
            future.value.get.get.id shouldBe portId
        }

        scenario("The port exists in the topology and is cached") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            backend.store.create(port)

            And("Requesting the port to update the VT cache")
            ready(intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[SimulationPort](portId)
            }.waitFor, timeout)

            When("Requesting the port a second time")
            val device = VirtualTopologyActor.tryAsk[SimulationPort](portId)

            Then("The request should return the port")
            device should not be null
            device.id shouldBe portId
        }

        scenario("The VT returns the latest port version") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            backend.store.create(port1)

            And("An awaitable observer to the virtual topology")
            val obs = new TestObserver[SimulationPort] with AwaitableObserver[SimulationPort]

            When("Requesting the port")
            // Try get the port, and wait for the port to be cached.
            val future = intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[SimulationPort](portId)
            }.waitFor.asInstanceOf[Future[SimulationPort]]
            ready(future, timeout)

            Then("The thrown future completes successfully with the port")
            future.isCompleted shouldBe true
            future.value should not be null
            future.value.get.get.id shouldBe portId
            future.value.get.get.tunnelKey shouldBe 1

            When("The observer subscribers to the port observable")
            vt.observables.get(portId).asInstanceOf[Observable[SimulationPort]]
                .subscribe(obs)

            And("The port is updated")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            backend.store.update(port2)

            And("Waiting for the port to update the topology")
            obs.awaitOnNext(2, timeout) shouldBe true

            And("Requesting the port")
            val device = VirtualTopologyActor.tryAsk[SimulationPort](portId)

            Then("The request should return the updated port")
            device should not be null
            device.id shouldBe portId
            device.tunnelKey shouldBe 2
        }

        scenario("The VT removes the port from cache on deletion") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            backend.store.create(port)

            And("Requesting the port to update the VT cache")
            ready(intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[SimulationPort](portId)
            }.waitFor, timeout)

            When("Deleting the port")
            val obs = new TestObserver[SimulationPort] with AwaitableObserver[SimulationPort]
            vt.observables.get(portId)
               .asInstanceOf[Observable[SimulationPort]]
               .subscribe(obs)

            backend.store.delete(classOf[TopologyPort], portId)
            // Waiting for the notification of the deletion.
            obs.awaitCompletion(timeout)

            And("Requesting the port a second time")
            val e1 = intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[SimulationPort](portId)
            }

            Then("The request throws a NotYetException with a future")
            val future = e1.waitFor.asInstanceOf[Future[SimulationPort]]

            When("Waiting for the future to complete")
            val e2 = intercept[Exception] {
                result(future, timeout)
            }.getCause.asInstanceOf[NotFoundException]

            Then("The future fails with a NotFoundException")
            e2.clazz shouldBe classOf[TopologyPort]
            e2.id shouldBe portId
        }
    }

    feature("Test request with update false") {
        scenario("The port does not exist") {
            Given("A random port identifier")
            val portId = UUID.randomUUID

            When("Sending a port request to the VTA and waiting for reply")
            // Note: the VirtualTopology only replies with a failure to
            // PromiseActorRef senders. All other senders will timeout.
            val future = VirtualTopologyActor
                .ask(PortRequest(portId, update = false))(timeout)

            When("And waiting for the virtual topology to reply")
            val e = intercept[Exception] {
                result(future, timeout)
            }.asInstanceOf[NotFoundException]

            Then("The virtual topology returns a NotFoundException")
            e.clazz shouldBe classOf[TopologyPort]
            e.id shouldBe portId
        }

        scenario("The port exists in the topology and is not cached") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            backend.store.create(port)

            When("Sending a port request to the VTA and waiting for reply")
            VirtualTopologyActor ! PortRequest(portId, update = false)
            sender.await(timeout)

            Then("The sender receives a message with the port")
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
            }
        }

        scenario("The port exists in the topology and is cached") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            backend.store.create(port)

            And("Requesting the port to update the VT cache")
            ready(intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[SimulationPort](portId)
            }.waitFor, timeout)

            When("Sending a port request to the VTA and waiting for reply")
            VirtualTopologyActor ! PortRequest(portId, update = false)
            sender.await(timeout)

            Then("The sender receives a message with the port")
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
            }
        }

        scenario("The sender receives only one update") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            backend.store.create(port1)

            And("An awaitable observer to the virtual topology")
            val obs = new TestObserver[SimulationPort] with AwaitableObserver[SimulationPort]

            When("Sending a port request to the VTA and waiting for reply")
            VirtualTopologyActor ! PortRequest(portId, update = false)
            sender.await(timeout)

            Then("The sender receives a message with the port")
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
                d.tunnelKey shouldBe 1
            }

            When("The observer subscribers to the port observable")
            vt.observables.get(portId).asInstanceOf[Observable[SimulationPort]]
                .subscribe(obs)

            And("The port is updated")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            backend.store.update(port2)

            And("Waiting for the port to update the topology")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("The sender should not have received the updated port")
            sender.messages.size shouldBe 1
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
                d.tunnelKey shouldBe 1
            }
        }
    }

    feature("Test request with update true") {
        scenario("The sender receives all updates") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            backend.store.create(port1)

            And("An awaitable observer to the virtual topology")
            val obs = new TestObserver[SimulationPort] with AwaitableObserver[SimulationPort]

            When("Sending a port request to the VTA and waiting for reply")
            VirtualTopologyActor ! PortRequest(portId, update = true)
            sender.await(timeout)

            Then("The sender receives a message with the port")
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
                d.tunnelKey shouldBe 1
            }

            When("The observer subscribers to the port observable")
            vt.observables.get(portId).asInstanceOf[Observable[SimulationPort]]
                .subscribe(obs)

            And("The port is updated")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            backend.store.update(port2)

            And("Waiting for the port to update the topology")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("The sender should have received the updated port")
            sender.messages.size shouldBe 2
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
                d.tunnelKey shouldBe 2
            }
        }

        scenario("The sender does not receive updates after unsubscribe") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            backend.store.create(port1)

            And("An awaitable observer to the virtual topology")
            val obs = new TestObserver[SimulationPort] with AwaitableObserver[SimulationPort]

            When("Sending a port request to the VTA and waiting for reply")
            VirtualTopologyActor ! PortRequest(portId, update = true)
            sender.await(timeout)

            Then("The sender receives a message with the port")
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
                d.tunnelKey shouldBe 1
            }

            When("The sender unsubscribes")
            VirtualTopologyActor ! Unsubscribe(portId)

            And("The observer subscribers to the port observable")
            vt.observables.get(portId).asInstanceOf[Observable[SimulationPort]]
                .subscribe(obs)

            And("The port is updated")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            backend.store.update(port2)

            And("Waiting for the port to update the topology")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("The sender should note have received the updated port")
            sender.messages.size shouldBe 1
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
                d.tunnelKey shouldBe 1
            }
        }

        scenario("The sender should not receive device deleted") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        tunnelKey = 1)
            backend.store.create(port)

            And("An awaitable observer to the virtual topology")
            val obs = new TestObserver[SimulationPort] with AwaitableObserver[SimulationPort]

            When("Sending a port request to the VTA and waiting for reply")
            VirtualTopologyActor ! PortRequest(portId, update = true)
            sender.await(timeout)

            Then("The sender receives a message with the port")
            expectLast { case d: BridgePort =>
                d.id shouldBe portId
                d.tunnelKey shouldBe 1
            }

            When("The observer subscribers to the port observable")
            vt.observables.get(portId).asInstanceOf[Observable[SimulationPort]]
            .subscribe(obs)

            And("The port is deleted")
            backend.store.delete(classOf[TopologyPort], portId)
            result(backend.store.exists(classOf[TopologyPort], portId),
                   timeout) shouldBe false

            And("Waiting for the port to update the topology")
            obs.awaitCompletion(timeout)

            Then("The sender should no have received other message")
            sender.messages.size shouldBe 1
        }
    }

    feature("Test flow invalidation") {
        scenario("Flow controller receives invalidation messages") {
            Given("A network port")
            val portId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 1)
            backend.store.create(port1)

            And("An awaitable observer to the virtual topology")
            val obs = new TestObserver[SimulationPort] with AwaitableObserver[SimulationPort]

            When("Sending a port request to the VTA and waiting for reply")
            VirtualTopologyActor ! PortRequest(portId, update = false)

            And("The observer subscribes to the port observable")
            vt.observables.get(portId).asInstanceOf[Observable[SimulationPort]]
                .subscribe(obs)

            And("Waiting for the port to update the topology")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("The flow should receive an invalidate message")
            flowInvalidator should invalidate(FlowTagger.tagForDevice(portId))
            flowInvalidator.clear()

            And("The port is updated")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         tunnelKey = 2)
            backend.store.update(port2)

            And("Waiting for the port to update the topology")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("The flow should receive an invalidate message")
            flowInvalidator should invalidate(FlowTagger.tagForDevice(portId))
        }
    }

    feature("Test unsupported messages are handled by the VTA") {
        scenario("Test any message") {
            When("Sending any message to the VTA")
            val msg = new AnyRef
            VirtualTopologyActor ! msg

            Then("The VTA should receive the message")
            vta.messages.size shouldBe 1
            vta.messages.last shouldBe msg
        }
    }

    feature("Test supported devices") {
        scenario("Support for bridge") {
            val proto = createBridge()
            backend.store create proto

            VirtualTopologyActor ! BridgeRequest(proto.getId)
            sender await timeout

            expectLast({
                case device: Bridge => device shouldBeDeviceOf proto
            })
        }

        scenario("Support for router") {
            val proto = createRouter()
            backend.store create proto

            VirtualTopologyActor ! RouterRequest(proto.getId)
            sender await timeout

            expectLast({
                case device: Router => device shouldBeDeviceOf proto
            })
        }

        scenario("Support for chain") {
            val proto = createChain()
            backend.store create proto

            VirtualTopologyActor ! ChainRequest(proto.getId)
            sender await timeout

            expectLast({
                case device: Chain => device shouldBeDeviceOf proto
            })
        }

        scenario("Support for IP address group") {
            val proto = createIPAddrGroup()
            backend.store create proto

            VirtualTopologyActor ! IPAddrGroupRequest(proto.getId)
            sender await timeout

            expectLast({
                case device: IPAddrGroup => device shouldBeDeviceOf proto
            })
        }

        scenario("Support for port group") {
            val proto = createPortGroup()
            backend.store create proto

            VirtualTopologyActor ! PortGroupRequest(proto.getId)
            sender await timeout

            expectLast({
                case device: PortGroup => device shouldBeDeviceOf proto
            })
        }

        scenario("Support for load balancer") {
            val proto = createLoadBalancer()
            backend.store create proto

            VirtualTopologyActor ! LoadBalancerRequest(proto.getId)
            sender await timeout

            expectLast({
                case device: LoadBalancer => device shouldBeDeviceOf proto
            })
        }

        scenario("Support for pool") {
            val proto = createPool()
            backend.store create proto

            VirtualTopologyActor ! PoolRequest(proto.getId)
            sender await timeout

            expectLast({
                case device: Pool => device shouldBeDeviceOf proto
            })
        }

        scenario("Support for pool health monitor map") {
            val hm = createHealthMonitor()
            val lb = createLoadBalancer()
            val pool = createPool(healthMonitorId = Some(hm.getId),
                                  loadBalancerId = Some(lb.getId))
            backend.store.create(hm)
            backend.store.create(lb)
            backend.store.create(pool)

            VirtualTopologyActor ! PoolHealthMonitorMapRequest(update = true)
            sender await timeout
            sender await timeout

            expectLast({
                case device: PoolHealthMonitorMap =>
                    device.mappings.size shouldBe 1
                    device.mappings.contains(fromProto(pool.getId)) shouldBe true
                    val phm = device.mappings.values.head
                    phm.loadBalancer shouldBeDeviceOf lb
                    phm.healthMonitor shouldBeDeviceOf hm
                    phm.poolMembers.isEmpty shouldBe true
                    phm.vips.isEmpty shouldBe true
            })
        }
    }
}
