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

package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.Await.{ready, result}
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.{Actor, Props}
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.DeviceDeletedException
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.devices.{BridgePort, Port}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class VirtualTopologyActorTest extends MidolmanSpec {

    private val timeout = 1 second

    private class SenderActor extends Actor {
        var messages = List[Any]()
        override def receive = {
            case msg => messages = messages :+ msg
        }
    }

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor
                                                      with MessageAccumulator))

    feature("The VTA returns port with tryAsk") {
        scenario("The port does not exist") {
            Given("A random port identifier")
            val id = UUID.randomUUID

            When("Requesting the port")
            val e1 = intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[Port](id)
            }

            Then("The request throws a NotYetException with a future")
            val future = e1.waitFor.asInstanceOf[Future[Port]]

            And("The future should throw an exception")
            val e = intercept[DeviceDeletedException] {
                result(future, timeout)
            }
            e.deviceId shouldBe id

            And("The VTA should have received 2 messages")
            val msg = VirtualTopologyActor.getAndClear()
            msg should have size 2
            msg(0).asInstanceOf[Ask].request shouldBe PortRequest(id, update = false)
            msg(1) shouldBe DeleteDevice(id)
        }

        scenario("The port exists and is not cached") {
            Given("A bridge port")
            val port = newBridgePort(newBridge("br0"))

            When("Requesting the port")
            // The port is not cached, the VT throws a not yet exception
            val e = intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[Port](port.getId)
            }

            Then("The request throws a NotYetException with a future")
            val future = e.waitFor.asInstanceOf[Future[Port]]
            ready(future, timeout)

            And("The future completes successfully with the given port")
            future.isCompleted shouldBe true
            future.value should not be None
            future.value.get.get.id shouldBe port.getId

            And("The VTA should have received 4 messages")
            val msg = VirtualTopologyActor.getAndClear()
            msg should have size 4
            msg(0).asInstanceOf[Ask].request shouldBe PortRequest(port.getId, update = false)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
        }

        scenario("The port exists and is cached") {
            Given("A bridge port")
            val bridge = newBridge("br0")
            val port = newBridgePort(bridge)

            And("Requesting the port to update the VT cache")
            // Try get the port, and wait for the port to be cached.
            ready(intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[Port](port.getId)
            }.waitFor, timeout)

            When("Requesting the port a second time")
            // Get the port again, this time from cache.
            val device = VirtualTopologyActor.tryAsk[Port](port.getId)

            Then("The request should return the port")
            device should not be null
            device.id shouldBe port.getId

            And("The VTA should have received 4 messages")
            val msg = VirtualTopologyActor.getAndClear()
            msg should have size 4
            msg(0).asInstanceOf[Ask].request shouldBe PortRequest(port.getId, update = false)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
        }

        scenario("The VTA handles port updates") {
            Given("A bridge port")
            val bridge = newBridge("br0")
            val port = newBridgePort(bridge, vlanId = Some(1.toShort))

            When("Requesting the port")
            // Try get the port, and wait for the port to be cached.
            val future = intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[Port](port.getId)
            }.waitFor.asInstanceOf[Future[Port]]
            ready(future, timeout)

            Then("The thrown future completes successfully with the port")
            future.isCompleted shouldBe true
            future.value should not be null
            future.value.get.get.id shouldBe port.getId
            future.value.get.get.adminStateUp shouldBe true

            And("The VTA should have received 4 messages")
            var msg = VirtualTopologyActor.getAndClear()
            msg should have size 4
            msg(0).asInstanceOf[Ask].request shouldBe PortRequest(port.getId, update = false)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))

            And("The port is updated")
            // Note: only some fields are allowed to be updated: see the
            // port ZK manager.
            updatePort(port.setAdminStateUp(false))

            And("Requesting the port")
            val device = VirtualTopologyActor.tryAsk[Port](port.getId)

            Then("The request should return the updated port")
            device should not be null
            device.id shouldBe port.getId
            device.adminStateUp shouldBe false

            And("The VTA should have received another 2 messages")
            msg = VirtualTopologyActor.getAndClear()
            msg should have size 2
            msg(0).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(1) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
        }
    }

    feature("The VTA allows subscription for port updates") {
        scenario("The port does not exist") {
            Given("A random port identifier")
            val id = UUID.randomUUID

            And("A subscribing actor")
            val sender = TestActorRef[SenderActor](Props(new SenderActor))

            When("Requesting the port")
            VirtualTopologyActor.tell(PortRequest(id, update = true), sender)

            Then("The sender should not receive any response")
            sender.underlyingActor.messages shouldBe empty

            And("The VTA should have received 2 messages")
            VirtualTopologyActor.getAndClear() should contain inOrderOnly(
                PortRequest(id, update = true),
                DeleteDevice(id))
        }

        scenario("The port exists and is not cached") {
            Given("A bridge port")
            val port = newBridgePort(newBridge("br0"))

            And("A subscribing actor")
            val sender = TestActorRef[SenderActor](Props(new SenderActor))

            When("Requesting the port")
            VirtualTopologyActor.tell(PortRequest(port.getId, update = true),
                                      sender)

            Then("The sender should receive the port")
            var msg = sender.underlyingActor.messages
            msg should have size 2
            msg(0).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId

            And("The VTA should have received 4 messages")
            msg = VirtualTopologyActor.getAndClear()
            msg should have size 4
            msg(0) shouldBe PortRequest(port.getId, update = true)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
        }

        scenario("The port exists and is cached") {
            Given("A bridge port")
            val bridge = newBridge("br0")
            val port = newBridgePort(bridge)

            And("A subscribing actor")
            val sender = TestActorRef[SenderActor](Props(new SenderActor))

            And("Requesting the port to update the VT cache")
            // Try get the port, and wait for the port to be cached.
            ready(intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[Port](port.getId)
            }.waitFor, timeout)

            When("Requesting the port")
            VirtualTopologyActor.tell(PortRequest(port.getId, update = true),
                                      sender)

            Then("The sender should receive the port")
            var msg = sender.underlyingActor.messages
            msg should have size 1
            msg(0).asInstanceOf[BridgePort].id shouldBe port.getId

            And("The VTA should have received 5 messages")
            msg = VirtualTopologyActor.getAndClear()
            msg should have size 5
            msg(0).asInstanceOf[Ask].request shouldBe PortRequest(port.getId, update = false)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
            msg(4) shouldBe PortRequest(port.getId, update = true)
        }

        scenario("Sender receives updates") {
            Given("A bridge port")
            val bridge = newBridge("br0")
            val port = newBridgePort(bridge, vlanId = Some(1.toShort))

            And("A subscribing actor")
            val sender = TestActorRef[SenderActor](Props(new SenderActor))

            When("Requesting the port")
            VirtualTopologyActor.tell(PortRequest(port.getId, update = true),
                                      sender)

            Then("The sender should receive the port")
            var msg = sender.underlyingActor.messages
            msg should have size 2
            msg(0).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId

            When("The port is updated")
            // Note: only some fields are allowed to be updated: see the
            // port ZK manager.
            updatePort(port.setAdminStateUp(false))

            Then("The sender should receive the updated port")
            msg = sender.underlyingActor.messages
            msg should have size 3
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId

            And("The VTA should have received 6 messages")
            msg = VirtualTopologyActor.getAndClear()
            msg should have size 6
            msg(0) shouldBe PortRequest(port.getId, update = true)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
            msg(4).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(5) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
        }

        scenario("Sender does not receive updates after unsubscribe") {
            Given("A bridge port")
            val bridge = newBridge("br0")
            val port = newBridgePort(bridge, vlanId = Some(1.toShort))

            And("A subscribing actor")
            val sender = TestActorRef[SenderActor](Props(new SenderActor))

            When("Requesting the port")
            VirtualTopologyActor.tell(PortRequest(port.getId, update = true),
                                      sender)

            Then("The sender should receive the port")
            var msg = sender.underlyingActor.messages
            msg should have size 2
            msg(0).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId

            When("The sender unsubscribes")
            VirtualTopologyActor.tell(Unsubscribe(port.getId), sender)

            When("The port is updated")
            // Note: only some fields are allowed to be updated: see the
            // port ZK manager.
            updatePort(port.setAdminStateUp(false))

            Then("The sender should not receive the updated port")
            msg = sender.underlyingActor.messages
            msg should have size 2

            And("The VTA should have received 7 messages")
            msg = VirtualTopologyActor.getAndClear()
            msg should have size 7
            msg(0) shouldBe PortRequest(port.getId, update = true)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
            msg(4) shouldBe Unsubscribe(port.getId)
            msg(5).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(6) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
        }

        scenario("Sender unsubscribing should not affect other subscribers") {
            Given("A bridge port")
            val bridge = newBridge("br0")
            val port = newBridgePort(bridge, vlanId = Some(1.toShort))

            And("A two subscribing actors")
            val sender1 = TestActorRef[SenderActor](Props(new SenderActor))
            val sender2 = TestActorRef[SenderActor](Props(new SenderActor))

            When("Requesting the port by the first sender")
            VirtualTopologyActor.tell(PortRequest(port.getId, update = true),
                                      sender1)

            Then("The sender should receive the port")
            var msg = sender1.underlyingActor.messages
            msg should have size 2
            msg(0).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId

            When("Requesting the port by the seconds sender")
            VirtualTopologyActor.tell(PortRequest(port.getId, update = true),
                                      sender2)

            Then("The sender should receive the port")
            msg = sender2.underlyingActor.messages
            msg should have size 1
            msg(0).asInstanceOf[BridgePort].id shouldBe port.getId

            When("The first sender unsubscribes")
            VirtualTopologyActor.tell(Unsubscribe(port.getId), sender1)

            When("The port is updated")
            // Note: only some fields are allowed to be updated: see the
            // port ZK manager.
            updatePort(port.setAdminStateUp(false))

            Then("The first sender should not receive the updated port")
            msg = sender1.underlyingActor.messages
            msg should have size 2

            And("The second sender should receive the updated port")
            msg = sender2.underlyingActor.messages
            msg should have size 2
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId

            And("The VTA should have received 8 messages")
            msg = VirtualTopologyActor.getAndClear()
            msg should have size 8
            msg(0) shouldBe PortRequest(port.getId, update = true)
            msg(1).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(2).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(3) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
            msg(4) shouldBe PortRequest(port.getId, update = true)
            msg(5) shouldBe Unsubscribe(port.getId)
            msg(6).asInstanceOf[BridgePort].id shouldBe port.getId
            msg(7) shouldBe InvalidateFlowsByTag(FlowTagger.tagForPort(port.getId))
        }
    }
}
