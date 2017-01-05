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

package org.midonet.midolman.vpp

import java.util
import java.util.concurrent.ExecutorService
import java.util.{Collections, UUID, List => JList}

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{CreateOp, InMemoryStorage}
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.midolman.vpp.VppProviderRouter.{Gateways, ProviderRouter}
import org.midonet.packets.{IPv6Addr, IPv6Subnet}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.logging.Logger
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class VppProviderRouterTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: InMemoryStorage = _
    private val timeout = 5 seconds

    private class TestableVppProviderRouter extends VppExecutor
                                                    with VppProviderRouter {
        override def hostId = HostIdGenerator.getHostId
        override def vt = VppProviderRouterTest.this.vt
        override val log = Logger(LoggerFactory.getLogger("vpp-fip64"))
        var messages = List[Any]()

        val observer = new TestObserver[ProviderRouter]
        providerRouterObservable subscribe observer

        protected override def newExecutor: ExecutorService = {
            new SameThreadButAfterExecutorService
        }

        protected override def receive: Receive = {
            case m =>
                log debug s"Received message $m"
                messages = messages :+ m
                Future.successful(Unit)
        }

        protected override def doStart(): Unit = {
            notifyStarted()
        }

        protected override def doStop(): Unit = {
            super.doStop()
            notifyStopped()
        }

        def add(portId: UUID, routerId: UUID, groupPortIds: JList[UUID]): Unit = {
            addUplink(portId, routerId, groupPortIds)
        }

        def remove(portId: UUID): Unit = {
            removeUplink(portId)
        }

        def getPort(portId: UUID): UUID = {
            uplinkPortFor(portId)
        }

        def getPorts(portId: UUID): JList[UUID] = {
            uplinkPortsFor(portId)
        }
    }

    protected override def beforeTest(): Unit = {
        HostIdGenerator.useTemporaryHostId()
        vt = injector.getInstance(classOf[VirtualTopology])
        store = vt.backend.store.asInstanceOf[InMemoryStorage]
    }

    private def randomSubnet6(): IPv6Subnet = {
        new IPv6Subnet(IPv6Addr.random, 64)
    }

    feature("Provider router instance manages uplinks") {
        scenario("Uplink port without port group") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding an uplink")
            vpp.add(port.getId.asJava, router.getId.asJava,
                    Collections.emptyList())

            Then("The uplink should be available for a downlink")
            vpp.getPort(UUID.randomUUID()) shouldBe port.getId.asJava

            And("The uplink ports should be empty")
            vpp.getPorts(port.getId.asJava) shouldBe empty

            When("Removing an uplink")
            vpp.remove(port.getId)

            Then("The uplink should not be available for a downlink")
            vpp.getPort(UUID.randomUUID()) shouldBe null
        }

        scenario("Uplink port with port group") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding an uplink")
            vpp.add(port.getId.asJava, router.getId.asJava,
                    Collections.singletonList(port.getId.asJava))

            Then("The uplink should be available for a downlink")
            vpp.getPort(UUID.randomUUID()) shouldBe port.getId.asJava

            And("The uplink ports should contain the port")
            vpp.getPorts(port.getId.asJava) should contain only port.getId.asJava
        }

        scenario("Multiple uplinks are not supported") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            store.multi(Seq(CreateOp(router), CreateOp(port1), CreateOp(port2)))

            When("Adding both uplink")
            vpp.add(port1.getId.asJava, router.getId.asJava,
                    Collections.emptyList())
            vpp.add(port2.getId.asJava, router.getId.asJava,
                    Collections.emptyList())

            Then("The uplink should not be available for a downlink")
            vpp.getPort(UUID.randomUUID()) shouldBe null
        }
    }

    feature("Provider router instance notifies gateways") {
        scenario("Uplink port without port group") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding an uplink")
            vpp.add(port.getId.asJava, router.getId.asJava,
                    Collections.emptyList())

            Then("The controller should not receive a notification")
            vpp.messages shouldBe empty
        }

        scenario("Uplink port with unbound group ports") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding an uplink")
            vpp.add(port.getId.asJava, router.getId.asJava,
                    Collections.singletonList(port.getId.asJava))

            Then("The controller should receive no gateways")
            vpp.messages(0) shouldBe Gateways(port.getId.asJava, Set())
        }

        scenario("Uplink port with bound group ports") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A port")
            val host = createHost()
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6(),
                                        hostId = Some(host.getId.asJava))
            store.multi(Seq(CreateOp(host), CreateOp(router),
                                    CreateOp(port)))

            When("Adding an uplink")
            vpp.add(port.getId.asJava, router.getId.asJava,
                    Collections.singletonList(port.getId.asJava))

            Then("The controller should receive no gateways")
            vpp.messages(0) shouldBe Gateways(port.getId.asJava, Set())

            When("The port becomes active")
            store.addValueAs(host.getId.asJava.toString, classOf[Port],
                             port.getId, ActiveKey, host.getId.asJava.toString)
                 .await(timeout)

            Then("The controller should receive the gateways")
            vpp.messages(1) shouldBe Gateways(port.getId.asJava, Set(host.getId))

            When("The port becomes inactive")
            store.removeValueAs(host.getId.asJava.toString, classOf[Port],
                                port.getId, ActiveKey, host.getId.asJava.toString)
                 .await(timeout)

            Then("The controller should receive no gateways")
            vpp.messages(2) shouldBe Gateways(port.getId.asJava, Set())
        }

        scenario("Uplink port with bound group ports to single gatways") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("Two ports")
            val host = createHost()
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6(),
                                         hostId = Some(host.getId.asJava))
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6(),
                                         hostId = Some(host.getId.asJava))
            store.multi(Seq(CreateOp(host), CreateOp(router),
                            CreateOp(port1), CreateOp(port2)))
            store.addValueAs(host.getId.asJava.toString, classOf[Port],
                             port1.getId, ActiveKey, host.getId.asJava.toString)
                 .await(timeout)

            When("Adding an uplink")
            vpp.add(port1.getId.asJava, router.getId.asJava,
                    List(port1.getId.asJava, port2.getId.asJava).asJava)

            Then("The controller should receive the gateways")
            vpp.messages(0) shouldBe Gateways(port1.getId.asJava, Set(host.getId))

            When("The second port becomes active")
            store.addValueAs(host.getId.asJava.toString, classOf[Port],
                             port2.getId, ActiveKey, host.getId.asJava.toString)
                 .await(timeout)

            Then("The controller receives no new messages")
            vpp.messages should have size 1

            When("The first port becomes inactive")
            store.removeValueAs(host.getId.asJava.toString, classOf[Port],
                                port1.getId, ActiveKey, host.getId.asJava.toString)
                 .await(timeout)

            Then("The controller receives no new messages")
            vpp.messages should have size 1

            When("The second port becomes inactive")
            store.removeValueAs(host.getId.asJava.toString, classOf[Port],
                                port2.getId, ActiveKey, host.getId.asJava.toString)
                 .await(timeout)

            Then("The controller should receive no gateways")
            vpp.messages(1) shouldBe Gateways(port1.getId.asJava, Set())
        }

        scenario("Uplink port with bound group ports to multiple gateways") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("Two ports")
            val host1 = createHost()
            val host2 = createHost()
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6(),
                                         hostId = Some(host1.getId.asJava))
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6(),
                                         hostId = Some(host2.getId.asJava))
            store.multi(Seq(CreateOp(host1), CreateOp(host2), CreateOp(router),
                            CreateOp(port1), CreateOp(port2)))
            store.addValueAs(host1.getId.asJava.toString, classOf[Port],
                             port1.getId, ActiveKey, host1.getId.asJava.toString)
                 .await(timeout)

            When("Adding an uplink")
            vpp.add(port1.getId.asJava, router.getId.asJava,
                    List(port1.getId.asJava, port2.getId.asJava).asJava)

            Then("The controller should receive the first gateway")
            vpp.messages(0) shouldBe Gateways(port1.getId.asJava, Set(host1.getId))

            When("The second port becomes active")
            store.addValueAs(host2.getId.asJava.toString, classOf[Port],
                             port2.getId, ActiveKey, host2.getId.asJava.toString)
                 .await(timeout)

            Then("The controller should receive both gateways")
            vpp.messages(1) shouldBe Gateways(port1.getId.asJava,
                                              Set(host1.getId, host2.getId))

            When("The first port becomes inactive")
            store.removeValueAs(host1.getId.asJava.toString, classOf[Port],
                                port1.getId, ActiveKey, host1.getId.asJava.toString)
                .await(timeout)

            Then("The controller should receive the second gateway")
            vpp.messages(2) shouldBe Gateways(port1.getId.asJava, Set(host2.getId))

            When("The second port becomes inactive")
            store.removeValueAs(host2.getId.asJava.toString, classOf[Port],
                                port2.getId, ActiveKey, host2.getId.asJava.toString)
                 .await(timeout)

            Then("The controller should receive no gateways")
            vpp.messages(3) shouldBe Gateways(port1.getId.asJava, Set())
        }
    }

    feature("Provider router instance notifies provider routers") {
        scenario("Router adding interior ports") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = randomSubnet6())
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding an uplink")
            vpp.add(port.getId, router.getId, Collections.emptyList())

            Then("The instance should emit a router with no ports")
            vpp.observer.getOnNextEvents.get(0) shouldBe ProviderRouter(
                router.getId, Collections.emptyMap())

            When("Adding an interior port")
            val bridge = createBridge()
            val peerPort1 = createBridgePort(bridgeId = Some(bridge.getId))
            val interiorPort1 = createRouterPort(routerId = Some(router.getId),
                                                 portSubnet = randomSubnet6(),
                                                 peerId = Some(peerPort1.getId))
            store.multi(Seq(CreateOp(bridge), CreateOp(peerPort1),
                            CreateOp(interiorPort1)))

            Then("The instance should emit a router with a port")
            vpp.observer.getOnNextEvents.get(1) shouldBe ProviderRouter(
                router.getId,
                Collections.singletonMap(interiorPort1.getId, peerPort1.getId))

            When("Adding a second interior port")
            val peerPort2 = createBridgePort(bridgeId = Some(bridge.getId))
            val interiorPort2 = createRouterPort(routerId = Some(router.getId),
                                                 portSubnet = randomSubnet6(),
                                                 peerId = Some(peerPort2.getId))
            store.multi(Seq(CreateOp(peerPort2), CreateOp(interiorPort2)))

            Then("The instance should emit a router with both ports")
            val map = new util.HashMap[UUID, UUID]()
            map.put(interiorPort1.getId, peerPort1.getId)
            map.put(interiorPort2.getId, peerPort2.getId)
            vpp.observer.getOnNextEvents.get(2) shouldBe ProviderRouter(
                router.getId, map)

            When("Deleting a router port")
            store.delete(classOf[Port], interiorPort1.getId)

            Then("The instance should emit a routre with a port")
            vpp.observer.getOnNextEvents.get(3) shouldBe ProviderRouter(
                router.getId,
                Collections.singletonMap(interiorPort2.getId, peerPort2.getId))
        }

        scenario("Router with existing interior port") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A router with an interior port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = randomSubnet6())
            val bridge = createBridge()
            val peerPort = createBridgePort(bridgeId = Some(bridge.getId))
            val interiorPort = createRouterPort(routerId = Some(router.getId),
                                                portSubnet = randomSubnet6(),
                                                peerId = Some(peerPort.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(bridge),
                        CreateOp(peerPort), CreateOp(interiorPort)))

            When("Adding an uplink")
            vpp.add(port.getId, router.getId, Collections.emptyList())

            Then("The instance should emit a router with a port")
            vpp.observer.getOnNextEvents.get(0) shouldBe ProviderRouter(
                router.getId,
                Collections.singletonMap(interiorPort.getId, peerPort.getId))
        }

        scenario("Provider router cleared when uplink removed") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A router with an interior port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = randomSubnet6())
            val bridge = createBridge()
            val peerPort = createBridgePort(bridgeId = Some(bridge.getId))
            val interiorPort = createRouterPort(routerId = Some(router.getId),
                                                portSubnet = randomSubnet6(),
                                                peerId = Some(peerPort.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(bridge),
                            CreateOp(peerPort), CreateOp(interiorPort)))

            When("Adding an uplink")
            vpp.add(port.getId, router.getId, Collections.emptyList())

            And("Removing the uplink")
            vpp.remove(port.getId)

            Then("The instance should emit a router with no ports")
            vpp.observer.getOnNextEvents.get(1) shouldBe ProviderRouter(
                router.getId, Collections.emptyMap())
        }

        scenario("Provider router cleared when router deleted") {
            Given("A provider router instance")
            val vpp = new TestableVppProviderRouter
            vpp.startAsync().awaitRunning()

            And("A router with an interior port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = randomSubnet6())
            val bridge = createBridge()
            val peerPort = createBridgePort(bridgeId = Some(bridge.getId))
            val interiorPort = createRouterPort(routerId = Some(router.getId),
                                                portSubnet = randomSubnet6(),
                                                peerId = Some(peerPort.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(bridge),
                            CreateOp(peerPort), CreateOp(interiorPort)))

            When("Adding an uplink")
            vpp.add(port.getId, router.getId, Collections.emptyList())

            And("Deleting the router")
            store.delete(classOf[Router], router.getId)

            Then("The instance should emit a router with no ports")
            vpp.observer.getOnNextEvents.get(1) shouldBe ProviderRouter(
                router.getId, Collections.emptyMap())
        }
    }

}
