/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman

import java.util.UUID

import scala.concurrent.Future
import scala.util.Random

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.scalatest.GivenWhenThen
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.HostRequestProxyTest.{TestableHostRequestProxy, TestableSubscriberActor, TestableVirtualToPhysicalMapper, TestableVirtualTopologyActor}
import org.midonet.midolman.simulation.DeviceDeletedException
import org.midonet.midolman.state.{FlowStateStorage, MockStateStorage}
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopologyActor}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{HostRequest, HostUnsubscribe}
import org.midonet.midolman.topology.VirtualTopologyActor.{Ask, PortRequest}
import org.midonet.midolman.topology.devices.{BridgePort, Host, Port}
import org.midonet.midolman.topology.rcu.{PortBinding, ResolvedHost}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.{EmptyActor, MessageAccumulator}

object HostRequestProxyTest {

    class TestableSubscriberActor extends EmptyActor with MessageAccumulator

    class TestableHostRequestProxy(hostId: UUID,
                                   storageFuture: Future[FlowStateStorage],
                                   subscriber: ActorRef)
        extends HostRequestProxy(hostId, storageFuture, subscriber)
        with MessageAccumulator

    class TestableVirtualTopologyActor extends VirtualTopologyActor {
        override def receive = { case _ => }
    }

    class TestableVirtualToPhysicalMapper extends VirtualToPhysicalMapper {
        override def receive = { case _ => }
    }

}

@RunWith(classOf[JUnitRunner])
class HostRequestProxyTest extends MidolmanSpec with GivenWhenThen {

    private val random = new Random()
    private val flowStateStorage = Future.successful(new MockStateStorage)
    private var subscriber: TestActorRef[TestableSubscriberActor] = _

    registerActors(VirtualTopologyActor -> (
        () => new TestableVirtualTopologyActor with MessageAccumulator))
    registerActors(VirtualToPhysicalMapper -> (
        () => new TestableVirtualToPhysicalMapper with MessageAccumulator))

    protected override def beforeTest(): Unit = {
        subscriber = TestActorRef[TestableSubscriberActor](
            Props(classOf[TestableSubscriberActor]))
    }

    private def createPort(portId: UUID): Port = {
        new BridgePort {
            id = portId
            tunnelKey = random.nextLong()
        }
    }

    feature("Handles host updates") {
        scenario("Proxy requests the host and unsubscribes") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            When("Starting a host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            Then("The VTPM should receive a host request")
            VirtualToPhysicalMapper.messages should contain only HostRequest(hostId)

            When("The the proxy is stopped")
            proxy.stop()

            Then("The VTPM should receive an unsubscribe")
            VirtualToPhysicalMapper.messages(1) shouldBe HostUnsubscribe(hostId)
        }

        scenario("Host with different identifiers") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(UUID.randomUUID(), alive = true, Map(), Map())

            Then("The subscriber should not receive the host")
            subscriber.underlyingActor.messages shouldBe empty
        }

        scenario("Host without bindings") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(), Map())

            Then("The subscriber should receive the host")
            subscriber.underlyingActor.messages should contain only
                ResolvedHost(hostId, alive = true, Map(), Map())
        }

        scenario("Host with a binding not cached in the topology") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port identifier")
            val portId = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(portId -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should contain only
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            val promise = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), p)
                    if portId == id => p
                case _ => fail("Unexpected message")
            }

            When("The VTA caches the port and completes the request")
            val port = createPort(portId)
            VirtualTopologyActor.add(portId, port)
            promise.trySuccess(port)

            Then("The subscriber should receive the host with binding")
            subscriber.underlyingActor.messages should have size 2
            subscriber.underlyingActor.messages(1) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(portId -> PortBinding(portId, "eth0", port.tunnelKey)),
                    Map())
        }

        scenario("Host with binding cached in the topology") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port cached in the topology")
            val port = createPort(UUID.randomUUID())
            VirtualTopologyActor.add(port.id, port)

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(port.id -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should have size 1
            subscriber.underlyingActor.messages.head shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(port.id -> PortBinding(port.id, "eth0", port.tunnelKey)),
                    Map())
        }

        scenario("Host with some bindings cached in the topology") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port cached in the topology")
            val port1 = createPort(UUID.randomUUID())
            VirtualTopologyActor.add(port1.id, port1)

            And("A port not cached")
            val portId2 = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true,
                             Map(port1.id -> "eth0", portId2 -> "eth1"),
                             Map())

            Then("The subscriber should receive the host with first binding")
            subscriber.underlyingActor.messages should have size 1
            subscriber.underlyingActor.messages.head shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(port1.id -> PortBinding(port1.id, "eth0", port1.tunnelKey)),
                    Map())

            And("The VTA should receive an Ask request for the second port")
            val promise = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), p)
                    if portId2 == id => p
                case _ => fail("Unexpected message")
            }

            When("The VTA caches the port and completes the request")
            val port2 = createPort(portId2)
            VirtualTopologyActor.add(portId2, port2)
            promise.trySuccess(port2)

            Then("The subscriber should receive the host with both binding")
            subscriber.underlyingActor.messages should have size 2
            subscriber.underlyingActor.messages(1) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(port1.id -> PortBinding(port1.id, "eth0", port1.tunnelKey),
                        port2.id -> PortBinding(port2.id, "eth1", port2.tunnelKey)),
                    Map())
        }

        scenario("Host with several port bindings not cached") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("Two port identifiers")
            val portId1 = UUID.randomUUID()
            val portId2 = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true,
                             Map(portId1 -> "eth0", portId2 -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should contain only
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for each port")
            VirtualTopologyActor.messages should have size 2

            // The order for the port requests is non-deterministic, therefore
            // port1 is not necessarily mapped to portId1. We use the same
            // interface name to avoid making the test too complex.
            When("The VTA caches and completes the request for the first port")
            val port1 = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), promise) =>
                    val port = createPort(id)
                    VirtualTopologyActor.add(id, port)
                    promise.trySuccess(port)
                    port
                case _ => fail("Unexpected message")
            }

            Then("The subscriber should receive the host with binding")
            subscriber.underlyingActor.messages should have size 2
            subscriber.underlyingActor.messages(1) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(port1.id -> PortBinding(port1.id, "eth0", port1.tunnelKey)),
                    Map())

            When("The VTA caches and completes the request for the second port")
            val port2 = VirtualTopologyActor.messages(1) match {
                case Ask(PortRequest(id, update), promise) =>
                    val port = createPort(id)
                    VirtualTopologyActor.add(id, port)
                    promise.trySuccess(port)
                    port
                case _ => fail("Unexpected message")
            }

            Then("The subscriber should receive the host with binding")
            subscriber.underlyingActor.messages should have size 3
            subscriber.underlyingActor.messages(2) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(port1.id -> PortBinding(port1.id, "eth0", port1.tunnelKey),
                        port2.id -> PortBinding(port2.id, "eth0", port2.tunnelKey)),
                    Map())
        }

        scenario("Non-cached port added while another pending") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port identifier")
            val portId1 = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(portId1 -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without bindings")
            subscriber.underlyingActor.messages should contain only
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            val promise1 = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), p)
                    if portId1 == id => p
                case _ => fail("Unexpected message")
            }

            When("Adding a new port binding")
            val portId2 = UUID.randomUUID()

            And("The proxy receives a host with the new port")
            proxy ! new Host(hostId, alive = true,
                             Map(portId1 -> "eth0", portId2 -> "eth1"),
                             Map())

            Then("The subscriber should receive the host without bindings")
            subscriber.underlyingActor.messages should have size 2
            subscriber.underlyingActor.messages(1) shouldBe
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            VirtualTopologyActor.messages should have size 2
            val promise2 = VirtualTopologyActor.messages(1) match {
                case Ask(PortRequest(id, update), p)
                    if portId2 == id => p
                case _ => fail("Unexpected message")
            }

            When("The VTA caches the port and completes the request for the second port")
            val port2 = createPort(portId2)
            VirtualTopologyActor.add(portId2, port2)
            promise2.trySuccess(port2)

            Then("The subscriber should receive the host with the second binding")
            subscriber.underlyingActor.messages should have size 3
            subscriber.underlyingActor.messages(2) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(port2.id -> PortBinding(port2.id, "eth1", port2.tunnelKey)),
                    Map())

            When("The VTA caches the port and completes the request for the first port")
            val port1 = createPort(portId1)
            VirtualTopologyActor.add(portId1, port1)
            promise1.trySuccess(port1)

            Then("The subscriber should receive the host with both bindings")
            subscriber.underlyingActor.messages should have size 4
            subscriber.underlyingActor.messages(3) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(portId1 -> PortBinding(portId1, "eth0", port1.tunnelKey),
                        port2.id -> PortBinding(port2.id, "eth1", port2.tunnelKey)),
                    Map())
        }

        scenario("Cached port added while another pending") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port identifier")
            val portId1 = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(portId1 -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should contain only
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            val promise = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), p)
                    if portId1 == id => p
                case _ => fail("Unexpected message")
            }

            When("Adding a new port binding already cached")
            val port2 = createPort(UUID.randomUUID())
            VirtualTopologyActor.add(port2.id, port2)

            And("The proxy receives a host with the new port")
            proxy ! new Host(hostId, alive = true,
                             Map(portId1 -> "eth0", port2.id -> "eth1"),
                             Map())

            Then("The subscriber should receive the host with the second binding")
            subscriber.underlyingActor.messages should have size 2
            subscriber.underlyingActor.messages(1) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(port2.id -> PortBinding(port2.id, "eth1", port2.tunnelKey)),
                    Map())

            When("The VTA caches the port and completes the request")
            val port1 = createPort(portId1)
            VirtualTopologyActor.add(portId1, port1)
            promise.trySuccess(port1)

            Then("The subscriber should receive the host with both bindings")
            subscriber.underlyingActor.messages should have size 3
            subscriber.underlyingActor.messages(2) shouldBe
                ResolvedHost(
                    hostId, alive = true,
                    Map(portId1 -> PortBinding(portId1, "eth0", port1.tunnelKey),
                        port2.id -> PortBinding(port2.id, "eth1", port2.tunnelKey)),
                    Map())
        }

        scenario("Port removed while pending") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port identifier")
            val portId = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(portId -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should contain only
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            val promise = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), p)
                    if portId == id => p
                case _ => fail("Unexpected message")
            }

            When("The proxy receives the host without the binding")
            proxy ! new Host(hostId, alive = true, Map(), Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should have size 2
            subscriber.underlyingActor.messages(1) shouldBe
                ResolvedHost(hostId, alive = true, Map(), Map())

            When("The VTA caches the port and completes the request")
            val port = createPort(portId)
            VirtualTopologyActor.add(portId, port)
            promise.trySuccess(port)

            Then("The subscriber should not receive another message")
            subscriber.underlyingActor.messages should have size 2
        }

        scenario("Port binding does not exist") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port identifier")
            val portId = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(portId -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should contain only
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            val promise = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), p)
                    if portId == id => p
                case _ => fail("Unexpected message")
            }

            When("The VTA completes the request")
            promise.tryFailure(DeviceDeletedException(portId))

            Then("The subscriber should not receive another message")
            subscriber.underlyingActor.messages should have size 1
        }

        scenario("Port binding error") {
            Given("A host identifier")
            val hostId = UUID.randomUUID()

            And("A port identifier")
            val portId = UUID.randomUUID()

            And("A host request proxy")
            val proxy = TestActorRef[TestableHostRequestProxy](
                Props(classOf[TestableHostRequestProxy], hostId,
                      flowStateStorage, subscriber))

            When("The proxy receives a host with different identifier")
            proxy ! new Host(hostId, alive = true, Map(portId -> "eth0"),
                             Map())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should contain only
            ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            VirtualTopologyActor.messages should have size 1
            val promise1 = VirtualTopologyActor.messages.head match {
                case Ask(PortRequest(id, update), p)
                    if portId == id => p
                case _ => fail("Unexpected message")
            }

            When("The VTA completes the request with an error")
            promise1.tryFailure(new Exception())

            Then("The subscriber should receive the host without binding")
            subscriber.underlyingActor.messages should have size 2
            subscriber.underlyingActor.messages(1) shouldBe
                ResolvedHost(hostId, alive = true, Map(), Map())

            And("The VTA should receive an Ask request for the port")
            VirtualTopologyActor.messages should have size 2
            val promise2 = VirtualTopologyActor.messages(1) match {
                case Ask(PortRequest(id, update), p)
                    if portId == id => p
                case _ => fail("Unexpected message")
            }
        }
    }
}
