/*
 * Copyright 2015 Midokura SARL
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

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.{Port => TopologyPort, Router => TopologyRouter}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.{Router => SimulationRouter}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPSubnet}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class RouterMapperTest extends MidolmanSpec with TopologyBuilder
                               with TopologyMatchers {

    import org.midonet.midolman.topology.TopologyBuilder._

    private var store: StorageWithOwnership = _
    private var vt: VirtualTopology = _
    private var config: MidolmanConfig = _
    private var threadId: Long = _

    private final val timeout = 5 seconds

    registerActors(FlowController -> (() => new FlowController
                                                with MessageAccumulator))

    def fc = FlowController.as[FlowController with MessageAccumulator]

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).ownershipStore
        config = injector.getInstance(classOf[MidolmanConfig])
        threadId = Thread.currentThread.getId
    }

    protected override def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        super.fillConfig(config)
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config
    }

    implicit def asIPSubnet(str: String): IPSubnet[_] = IPSubnet.fromString(str)

    def flowOf(srcAddress: String, dstAddress: String): FlowMatch = {
        new FlowMatch()
            .setNetworkSrc(IPAddr.fromString(srcAddress))
            .setNetworkDst(IPAddr.fromString(dstAddress))
    }

    private def createObserver(count: Int = 1)
    : AwaitableObserver[SimulationRouter] = {
        Given("An observer for the router mapper")
        // It is possible to receive the initial notification on the current
        // thread, when the device was notified in the mapper's behavior subject
        // previous to the subscription.
        new AwaitableObserver[SimulationRouter](
            count, assert(vt.vtThreadId == Thread.currentThread.getId ||
                          threadId == Thread.currentThread.getId))
    }

    private def createExteriorPort(routerId: UUID, adminStateUp: Boolean = true)
    : TopologyPort = {
        createRouterPort(routerId = Some(routerId),
                         adminStateUp = adminStateUp,
                         hostId = Some(UUID.randomUUID),
                         interfaceName = Some("iface0"))
    }

    private def testRouterCreated(obs: AwaitableObserver[SimulationRouter],
                                  count: Int, test: Int)
    : TopologyRouter = {
        Given("A router mapper")
        val routerId = UUID.randomUUID
        val mapper = new RouterMapper(routerId, vt)

        And("A router")
        val router = createRouter(id = routerId)

        When("The router is created")
        store.create(router)

        And("The observer subscribes to an observable on the mapper")
        Observable.create(mapper).subscribe(obs)

        Then("The observer should receive the router device")
        obs.await(5 seconds, count) shouldBe true
        val device = obs.getOnNextEvents.get(test)
        device shouldBeDeviceOf router

        router
    }

    private def testRouterUpdated(router: TopologyRouter,
                                  obs: AwaitableObserver[SimulationRouter],
                                  count: Int, test: Int)
    : SimulationRouter = {
        When("The router is updated")
        store.update(router)

        Then("The observer should receive the update")
        obs.await(timeout, count) shouldBe true
        val device = obs.getOnNextEvents.get(test)
        device shouldBeDeviceOf router

        device
    }

    private def testRouterDeleted(routerId: UUID,
                                  obs: AwaitableObserver[SimulationRouter],
                                  count: Int)
    : Unit = {
        When("The router is deleted")
        store.delete(classOf[TopologyRouter], routerId)

        Then("The observer should receive a completed notification")
        obs.await(timeout, count) shouldBe true
        obs.getOnCompletedEvents should not be empty
    }

    feature("Router mapper emits notifications for router update") {
        scenario("The mapper emits error for non-existing router") {
            Given("A router identifier")
            val routerId = UUID.randomUUID

            And("A router mapper")
            val mapper = new RouterMapper(routerId, vt)

            And("An observer to the router mapper")
            val obs = new AwaitableObserver[SimulationRouter]

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.await(timeout) shouldBe true
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyRouter]
            e.id shouldBe routerId
        }

        scenario("The mapper emits existing router") {
            val obs = createObserver(1)
            testRouterCreated(obs, count = 0, test = 0)
        }

        scenario("The mapper emits new device on router update") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 1, test = 0)
            val routerUpdate = createRouter(id = router.getId, adminStateUp = true)
            testRouterUpdated(routerUpdate, obs, count = 0, test = 1)
        }

        scenario("The mapper completes on router delete") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 1, test = 0)
            testRouterDeleted(router.getId, obs, count = 0)
        }
    }

    feature("Test port route updates") {
        scenario("Create exterior port without route") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 1, test = 0)

            And("The router should have the administrative state down")
            val device1 = obs.getOnNextEvents.get(0)
            device1.cfg.adminStateUp shouldBe false

            When("Creating an exterior port for the router")
            val port = createExteriorPort(router.getId)
            store.create(port)

            Then("The observer should receive a router update")
            obs.await(timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf router
        }

        scenario("Create exterior port with a route, port inactive") {
            val obs = createObserver(1)
            val router1 = testRouterCreated(obs, count = 1, test = 0)

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router1.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            And("Waiting for the first router notification")
            obs.await(timeout, 1)

            And("Updating the router to generate another notification")
            val router2 = router1.addPortId(port.getId).setAdminStateUp(true)
            store.update(router2)

            Then("The observer should receive a router update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router2
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Create exterior port with a route, port active up") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 2, test = 0)

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateWithOwnerOp(port, UUID.randomUUID.toString),
                            CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            Then("The observer should receive two router updates")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(port.getId).asJava
        }

        scenario("Create exterior port with a route, port active down") {
            val obs = createObserver(1)
            val router1 = testRouterCreated(obs, count = 1, test = 0)

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router1.getId, adminStateUp = false)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateWithOwnerOp(port, UUID.randomUUID.toString),
                            CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            And("Waiting for the first router notification")
            obs.await(timeout, 1) shouldBe true

            And("Updating the router to generate another notification")
            val router2 = router1.addPortId(port.getId).setAdminStateUp(true)
            store.update(router2)

            Then("The observer should receive a router update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router2
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Create interior port with a route, admin state up") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 2, test = 0)

            When("Creating an interior port with a route")
            val portId = UUID.randomUUID
            val routeId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val port = createRouterPort(id = portId,
                                        routerId = Some(router.getId),
                                        adminStateUp = true)
            val peerPort = createBridgePort(id = peerPortId)
            val route = createRoute(id = routeId,
                                  srcNetwork = "1.0.0.0/24",
                                  dstNetwork = "2.0.0.0/24",
                                  nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(peerPort), CreateOp(route),
                            UpdateOp(port.setPeerId(peerPortId)),
                            UpdateOp(route.setNextHopPortId(portId))))

            Then("The observer should receive two router updates")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(portId).asJava
        }

        scenario("Create interior port with a route, admin state down") {
            val obs = createObserver(1)
            val router1 = testRouterCreated(obs, count = 1, test = 0)

            When("Creating an interior port with a route")
            val portId = UUID.randomUUID
            val routeId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val port = createRouterPort(id = portId,
                                        routerId = Some(router1.getId),
                                        adminStateUp = false)
            val peerPort = createBridgePort(id = peerPortId)
            val route = createRoute(id = routeId,
                                  srcNetwork = "1.0.0.0/24",
                                  dstNetwork = "2.0.0.0/24",
                                  nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(peerPort), CreateOp(route),
                            UpdateOp(port.setPeerId(peerPortId)),
                            UpdateOp(route.setNextHopPortId(portId))))

            And("Waiting for the first router notification")
            obs.await(timeout, 1)

            And("Updating the router to generate another notification")
            val router2 = router1.addPortId(portId).setAdminStateUp(true)
            store.update(router2)

            Then("The observer should receive a router update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router2
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Route removed when exterior port becomes inactive") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 2, test = 0)

            When("Creating an exterior port with a route")
            val ownerId = UUID.randomUUID.toString
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateWithOwnerOp(port, ownerId),
                            CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            Then("The observer should receive two router updates")
            obs.await(timeout, 1) shouldBe true

            When("The port becomes inactive")
            store.deleteOwner(classOf[TopologyPort], port.getId, ownerId)

            Then("The observer should receive a router update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Route removed when exterior port becomes down") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 2, test = 0)

            When("Creating an exterior port with a route")
            val ownerId = UUID.randomUUID.toString
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateWithOwnerOp(port, ownerId),
                            CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            Then("The observer should receive two router updates")
            obs.await(timeout, 1) shouldBe true

            When("The port becomes administratively down")
            store.update(port.addRouteId(route.getId).setAdminStateUp(false))

            Then("The observer should receive a router update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Route removed when interior port becomes down") {
            val obs = createObserver(1)
            val router = testRouterCreated(obs, count = 2, test = 0)

            When("Creating an interior port with a route")
            val portId = UUID.randomUUID
            val routeId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val port = createRouterPort(id = portId,
                                        routerId = Some(router.getId),
                                        adminStateUp = true)
            val peerPort = createBridgePort(id = peerPortId)
            val route = createRoute(id = routeId,
                                    srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(peerPort), CreateOp(route),
                            UpdateOp(port.setPeerId(peerPortId)),
                            UpdateOp(route.setNextHopPortId(portId))))

            Then("The observer should receive two router updates")
            obs.await(timeout, 1) shouldBe true

            When("The port becomes inactive")
            store.update(port.addRouteId(route.getId).setAdminStateUp(false))

            Then("The observer should receive a router update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Route added when active exterior port becomes up") {
        }

        scenario("Route added when interior port becomes up") {
        }

        scenario("Port route added") {
        }

        scenario("Port route updated") {
        }

        scenario("Port route removed") {
        }
    }

    feature("Test router route updates") {
        scenario("Router route added") {
        }

        scenario("Router route updated") {
        }

        scenario("Router route deleted") {
        }
    }

    feature("Test router ARP table") {
        scenario("The router mapper creates a unique ARP table") {
        }
    }

    feature("Test router tag manager") {
        scenario("The router mapper provides a tag manager") {
        }
    }

}
