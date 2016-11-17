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
import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.{Mirror => TopologyMirror, Port => TopologyPort, Route => TopologyRoute, Router => TopologyRouter}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.ActiveKey
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.simulation.{Chain, Mirror, RouterPort, Router => SimulationRouter}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.FlowMatch
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.tagForRouter
import org.midonet.util.concurrent._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class RouterMapperTest extends MidolmanSpec with TopologyBuilder
                               with TopologyMatchers with Eventually {

    import TopologyBuilder._

    private var store: InMemoryStorage = _
    private var stateStore: StateStorage = _
    private var vt: VirtualTopology = _
    private var threadId: Long = _

    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store.asInstanceOf[InMemoryStorage]
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
        threadId = Thread.currentThread.getId
    }

    implicit def asIPSubnet(str: String): IPSubnet[_] = IPSubnet.fromCidr(str)
    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)
    implicit def asMAC(str: String): MAC = MAC.fromString(str)
    implicit def asRoute(str: String): Route =
        new Route(0, 0, IPv4Addr(str).toInt, 32, null, null, 0, 0, null, null)

    def flowOf(srcAddress: String, dstAddress: String): FlowMatch = {
        new FlowMatch()
            .setNetworkSrc(IPAddr.fromString(srcAddress))
            .setNetworkDst(IPAddr.fromString(dstAddress))
    }

    private def createObserver(): DeviceObserver[SimulationRouter] = {
        Given("An observer for the router mapper")
        // It is possible to receive the initial notification on the current
        // thread, when the device was notified in the mapper's behavior subject
        // previous to the subscription.
        new DeviceObserver[SimulationRouter](vt)
    }

    private def createExteriorPort(routerId: UUID, adminStateUp: Boolean = true)
    : TopologyPort = {
        createRouterPort(routerId = Some(routerId),
                         adminStateUp = adminStateUp,
                         hostId = Some(InMemoryStorage.namespaceId),
                         interfaceName = Some("iface0"))
    }

    private def createLearnedRoute(srcNetwork: IPSubnet[_],
                                   dstNetwork: IPSubnet[_],
                                   portId: UUID): Route = {

        new Route(srcNetwork.asInstanceOf[IPv4Subnet].getIntAddress,
                  srcNetwork.getPrefixLen,
                  dstNetwork.asInstanceOf[IPv4Subnet].getIntAddress,
                  dstNetwork.getPrefixLen,
                  Route.NextHop.PORT, portId, /*gateway*/0, /*weight*/100, "",
                  portId, /*learned*/true)
    }

    private def testRouterCreated(obs: DeviceObserver[SimulationRouter])
    : (TopologyRouter, RouterMapper) = {
        Given("A router mapper")
        val routerId = UUID.randomUUID
        val mapper = new RouterMapper(routerId, vt, mutable.Map())

        And("A router")
        val router = createRouter(id = routerId)

        When("The router is created")
        store.create(router)

        And("The observer subscribes to an observable on the mapper")
        Observable.create(mapper).subscribe(obs)

        Then("The observer should receive the router device")
        obs.awaitOnNext(1, timeout) shouldBe true
        val device = obs.getOnNextEvents.get(0)
        device shouldBeDeviceOf router

        (router, mapper)
    }

    private def testRouterUpdated(router: TopologyRouter,
                                  obs: DeviceObserver[SimulationRouter],
                                  event: Int): SimulationRouter = {
        When("The router is updated")
        store.update(router)

        Then("The observer should receive the update")
        obs.awaitOnNext(event, timeout) shouldBe true
        val device = obs.getOnNextEvents.get(event - 1)
        device shouldBeDeviceOf router

        device
    }

    private def testRouterDeleted(routerId: UUID,
                                  obs: DeviceObserver[SimulationRouter]): Unit = {
        When("The router is deleted")
        store.delete(classOf[TopologyRouter], routerId)

        Then("The observer should receive a completed notification")
        obs.awaitCompletion(timeout)
        obs.getOnCompletedEvents should not be empty
    }

    feature("Router mapper emits notifications for router update") {
        scenario("The mapper emits error for non-existing router") {
            Given("A router identifier")
            val routerId = UUID.randomUUID

            And("A router mapper")
            val mapper = new RouterMapper(routerId, vt, mutable.Map())

            And("An observer to the router mapper")
            val obs = createObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyRouter]
            e.id shouldBe routerId
        }

        scenario("The mapper emits existing router") {
            val obs = createObserver()
            testRouterCreated(obs)
        }

        scenario("The mapper emits new device on router update") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1
            val routerUpdate = createRouter(id = router.getId, adminStateUp = true)
            testRouterUpdated(routerUpdate, obs, event = 2)
        }

        scenario("The mapper completes on router delete") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1
            testRouterDeleted(router.getId, obs)
        }
    }

    feature("Test port route updates") {
        scenario("Create exterior port without route") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            And("The router should have the administrative state down")
            val device1 = obs.getOnNextEvents.get(0)
            device1.cfg.adminStateUp shouldBe false

            When("Creating an exterior port for the router")
            val port = createExteriorPort(router.getId)
            store.create(port)

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf router
        }

        scenario("Create exterior port with a route, port inactive") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router1.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            And("Waiting for the first router notification")
            obs.awaitOnNext(2, timeout)

            And("Updating the router to generate another notification")
            val router2 = router1.addPortId(port.getId).setAdminStateUp(true)
            store.update(router2)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router2
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Create exterior port with a route, port active up") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(port.getId).asJava
        }

        scenario("Create exterior port with a route, port active down") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router1.getId, adminStateUp = false)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            And("Waiting for the first router notification")
            obs.awaitOnNext(2, timeout) shouldBe true

            And("Updating the router to generate another notification")
            val router2 = router1.addPortId(port.getId).setAdminStateUp(true)
            store.update(router2)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router2
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Recreate exterior port with a route, port active up") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1
            When("Creating an exterior port with a route")
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))
            obs.awaitOnNext(2, timeout) shouldBe true
            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)
            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf router
            device1.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
            route.setNextHopPortId(port.getId).asJava
            When("Deleting the port")
            store.delete(classOf[TopologyPort], port.getId)
            Then("The observer should receive a router update")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(3)
            device2 shouldBeDeviceOf router
            device2.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
            When("Recreating the port")
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))
            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)
            Then("The observer should receive a router update")
            obs.awaitOnNext(6, timeout) shouldBe true
            val device3 = obs.getOnNextEvents.get(5)
            device3 shouldBeDeviceOf router
            device3.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
            route.setNextHopPortId(port.getId).asJava
        }

        scenario("Create interior port with a route, admin state up") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

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

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(portId).asJava
        }

        scenario("Create interior port with a route, admin state down") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1

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
            obs.awaitOnNext(2, timeout)

            And("Updating the router to generate another notification")
            val router2 = router1.addPortId(portId).setAdminStateUp(true)
            store.update(router2)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router2
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Route removed when exterior port becomes inactive") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port with a route")
            val ownerId = UUID.randomUUID.toString
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                ownerId).await(timeout)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true

            When("The port becomes inactive")
            stateStore.removeValue(classOf[TopologyPort], port.getId, ActiveKey,
                                   ownerId).await(timeout)

            Then("The observer should receive a router update")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Route removed when exterior port becomes down") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The port becomes administratively down")
            store.update(port.addRouteId(route.getId).setAdminStateUp(false))

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Route kept when interior port becomes down") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

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

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The port becomes inactive")
            store.update(port.addRouteId(route.getId).setPeerId(peerPortId)
                             .setAdminStateUp(false))

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(portId).asJava
        }

        scenario("Route added when active exterior port becomes up") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an inactive exterior port with a route")
            val ownerId = UUID.randomUUID.toString
            val port = createExteriorPort(router.getId, adminStateUp = false)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                ownerId).await(timeout)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true

            When("The port becomes up")
            store.update(port.addRouteId(route.getId).setAdminStateUp(true))

            Then("The observer should receive a router update")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(port.getId).asJava
        }

        scenario("Route added when active exterior port becomes active") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an inactive exterior port with a route")
            val ownerId = UUID.randomUUID.toString
            val port = createExteriorPort(router.getId)
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route),
                            UpdateOp(route.setNextHopPortId(port.getId))))

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                ownerId).await(timeout)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(port.getId).asJava
        }

        scenario("Route added when interior port becomes up") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an interior port with a route")
            val portId = UUID.randomUUID
            val routeId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val port = createRouterPort(id = portId,
                                        routerId = Some(router.getId),
                                        adminStateUp = false)
            val peerPort = createBridgePort(id = peerPortId)
            val route = createRoute(id = routeId,
                                    srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(peerPort), CreateOp(route),
                            UpdateOp(port.setPeerId(peerPortId)),
                            UpdateOp(route.setNextHopPortId(portId))))

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The port becomes up")
            store.update(port.addRouteId(route.getId).setPeerId(peerPortId)
                             .setAdminStateUp(true))

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(port.getId).asJava
        }

        scenario("Port route added") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port")
            val port = createExteriorPort(router.getId)
            store.create(port)
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)

            Then("The observer should receive a router update and no routes")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf router
            device1.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty

            When("Adding a route to the port")
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT,
                                    nextHopPortId = Some(port.getId))
            store.create(route)

            Then("The observer should receive a router update with the route")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(3)
            device2 shouldBeDeviceOf router
            device2.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.setNextHopPortId(port.getId).asJava
        }

        scenario("Port route updated") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router.getId)
            val route1 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "2.0.0.0/24",
                                     nextHop = NextHop.PORT)
            store.multi(Seq(CreateOp(port), CreateOp(route1),
                            UpdateOp(route1.setNextHopPortId(port.getId))))
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true

            When("The route is updated")
            val route2 = route1.setDstNetwork("3.0.0.0/24")
                               .setNextHopPortId(port.getId)
            store.update(route2)

            Then("The observer should receive a router update with new route")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
            device.rTable.lookup(flowOf("1.0.0.0", "3.0.0.0")) should contain only
                route2.asJava
        }

        scenario("Port route removed") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port")
            val port = createExteriorPort(router.getId)
            store.create(port)
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true

            When("Adding a route to the port")
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    nextHop = NextHop.PORT,
                                    nextHopPortId = Some(port.getId))
            store.create(route)

            Then("The observer should receive a router update")
            obs.awaitOnNext(4, timeout) shouldBe true

            When("The route is deleted")
            store.delete(classOf[TopologyRoute], route.getId)

            Then("The observer should receive a router update")
            obs.awaitOnNext(5, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(4)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Mapper does not emit router until all ports are loaded") {
            Given("A router with two exterior ports")
            val router = createRouter()
            val route1 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "2.0.0.0/24",
                                     routerId = Some(router.getId))
            val route2 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "3.0.0.0/24",
                                     routerId = Some(router.getId))
            val port1 = createExteriorPort(routerId = router.getId)
                .addRouteId(route1.getId)
            val port2 = createExteriorPort(routerId = router.getId)
                .addRouteId(route2.getId)
            store.multi(Seq(CreateOp(router), CreateOp(route1), CreateOp(route2),
                            CreateOp(port1), CreateOp(port2)))

            And("A router observer")
            val obs = createObserver()

            And("A router mapper")
            val mapper = new RouterMapper(router.getId, vt, mutable.Map())

            And("Requesting the ports to have them cached")
            VirtualTopology.get(classOf[RouterPort], port1.getId)
                           .await(timeout) shouldBeDeviceOf port1
            VirtualTopology.get(classOf[RouterPort], port2.getId)
                           .await(timeout) shouldBeDeviceOf port2

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the router device")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only route1
                .setNextHopPortId(port1.getId).asJava
            device.rTable.lookup(flowOf("1.0.0.0", "3.0.0.0")) should contain only route2
                .setNextHopPortId(port2.getId).asJava
        }

        scenario("Mapper does not emit router until all port routes are loaded") {
            Given("A router with an exterior port and two routes")
            val router = createRouter()
            val route1 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "2.0.0.0/24",
                                     routerId = Some(router.getId))
            val route2 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "3.0.0.0/24",
                                     routerId = Some(router.getId))
            val port = createExteriorPort(routerId = router.getId)
                .addRouteId(route1.getId).addRouteId(route2.getId)
            store.multi(Seq(CreateOp(router), CreateOp(route1), CreateOp(route2),
                            CreateOp(port)))

            And("A router observer")
            val obs = createObserver()

            And("A router mapper")
            val mapper = new RouterMapper(router.getId, vt, mutable.Map())

            And("Requesting the routes to have them cached")
            val obsRoute = new TestObserver[TopologyRoute]
                               with AwaitableObserver[TopologyRoute]
            store.observable(classOf[TopologyRoute], route1.getId)
                 .subscribe(obsRoute)
            store.observable(classOf[TopologyRoute], route2.getId)
                 .subscribe(obsRoute)
            obsRoute.awaitOnNext(2, timeout) shouldBe true

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the router device")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only route1
                .setNextHopPortId(port.getId).asJava
            device.rTable.lookup(flowOf("1.0.0.0", "3.0.0.0")) should contain only route2
                .setNextHopPortId(port.getId).asJava
        }
    }

    feature("Test router route updates") {
        scenario("Router route added") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Adding a route to the router")
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    routerId = Some(router.getId))
            store.create(route)

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route.asJava
        }

        scenario("Router route updated") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Adding a route to the router")
            val route1 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "2.0.0.0/24",
                                     routerId = Some(router.getId))
            store.create(route1)

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The route is updated")
            val route2 = route1.setDstNetwork("3.0.0.0/24")
            store.update(route2)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
            device.rTable.lookup(flowOf("1.0.0.0", "3.0.0.0")) should contain only
                route2.asJava
        }

        scenario("Router route deleted") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Adding a route to the router")
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    routerId = Some(router.getId))
            store.create(route)

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The route is deleted")
            store.delete(classOf[TopologyRoute], route.getId)

            Then("The observer should receive a router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Mapper does not emit router until all routes are loaded") {
            Given("A router with two routes")
            val router = createRouter()
            val route1 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "2.0.0.0/24",
                                     routerId = Some(router.getId))
            val route2 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "3.0.0.0/24",
                                     routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(route1),
                            CreateOp(route2)))

            And("A router observer")
            val obs = new DeviceObserver[SimulationRouter](vt)

            And("A router mapper")
            val mapper = new RouterMapper(router.getId, vt, mutable.Map())

            And("Requesting the routes to have them cached in store")
            val obsRoute = new TestObserver[TopologyRoute]
                               with AwaitableObserver[TopologyRoute]
            vt.store.observable(classOf[TopologyRoute], route1.getId)
                    .subscribe(obsRoute)
            vt.store.observable(classOf[TopologyRoute], route2.getId)
                    .subscribe(obsRoute)
            obsRoute.awaitOnNext(2, timeout) shouldBe true

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receove the router device")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only route1.asJava
            device.rTable.lookup(flowOf("1.0.0.0", "3.0.0.0")) should contain only route2.asJava
        }
    }

    feature("Test learned route updates") {
        scenario("Learned route added and removed") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port")
            val port = createExteriorPort(router.getId)
            store.create(port)
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)

            Then("The observer should receive a router update and no routes")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf router
            device1.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty

            When("Adding a learned route to the port")
            val route = createLearnedRoute(srcNetwork = "1.0.0.0/24",
                                           dstNetwork = "2.0.0.0/24",
                                           portId = port.getId)
            stateStore.addRoute(route).await(timeout)

            Then("The observer should receive a router update with the route")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(3)
            device2 shouldBeDeviceOf router
            device2.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route

            When("The route is deleted")
            stateStore.removeRoute(route).await(timeout)

            Then("The observer should receive a router update with no route")
            obs.awaitOnNext(5, timeout) shouldBe true
            val device3 = obs.getOnNextEvents.get(4)
            device3 shouldBeDeviceOf router
            device3.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Learned route added when port becomes active") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port")
            val port = createExteriorPort(router.getId)
            store.create(port)
            obs.awaitOnNext(2, timeout) shouldBe true

            When("Adding a learned route to the port")
            val route = createLearnedRoute(srcNetwork = "1.0.0.0/24",
                                           dstNetwork = "2.0.0.0/24",
                                           portId = port.getId)
            stateStore.addRoute(route).await(timeout)

            Then("The observer should receive a router update with no routes")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf router
            device1.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty

            When("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)

            Then("The observer should receive a router update with the route")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(3)
            device2 shouldBeDeviceOf router
            device2.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route
        }

        scenario("Learned route removed when port becomes inactive") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port")
            val port = createExteriorPort(router.getId)
            store.create(port)
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            val ownerId = UUID.randomUUID.toString
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                ownerId).await(timeout)

            Then("The observer should receive a router update and no routes")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf router
            device1.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty

            When("Adding a learned route to the port")
            val route = createLearnedRoute(srcNetwork = "1.0.0.0/24",
                                           dstNetwork = "2.0.0.0/24",
                                           portId = port.getId)
            stateStore.addRoute(route).await(timeout)

            Then("The observer should receive a router update with the route")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(3)
            device2 shouldBeDeviceOf router
            device2.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route

            When("The port becomes inactive")
            stateStore.removeValue(classOf[TopologyPort], port.getId, ActiveKey,
                                   ownerId).await(timeout)

            Then("The observer should receive a router update with no route")
            obs.awaitOnNext(5, timeout) shouldBe true
            val device3 = obs.getOnNextEvents.get(4)
            device3 shouldBeDeviceOf router
            device3.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) shouldBe empty
        }

        scenario("Learned routes are independent from static routes") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Creating an exterior port with a route")
            val port = createExteriorPort(router.getId)
            val route1 = createRoute(srcNetwork = "1.0.0.0/24",
                                     dstNetwork = "2.0.0.0/24",
                                     nextHop = NextHop.PORT,
                                     weight = Some(200))
            store.multi(Seq(CreateOp(port), CreateOp(route1),
                            UpdateOp(route1.setNextHopPortId(port.getId))))
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The port becomes active")
            stateStore.addValue(classOf[TopologyPort], port.getId, ActiveKey,
                                UUID.randomUUID.toString).await(timeout)

            Then("The observer should receive a router update with the route")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router
            device.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route1.setNextHopPortId(port.getId).asJava

            When("Adding a learned route to the port with a smaller metric")
            val route2 = createLearnedRoute(srcNetwork = "1.0.0.0/24",
                                            dstNetwork = "2.0.0.0/24",
                                            portId = port.getId)
            stateStore.addRoute(route2).await(timeout)

            Then("The observer should receive a router update with the route")
            obs.awaitOnNext(4, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(3)
            device2 shouldBeDeviceOf router
            device2.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route2

            When("The learned route is deleted")
            stateStore.removeRoute(route2).await(timeout)

            Then("The observer should receive a router update with the initial route")
            obs.awaitOnNext(5, timeout) shouldBe true
            val device3 = obs.getOnNextEvents.get(4)
            device3 shouldBeDeviceOf router
            device3.rTable.lookup(flowOf("1.0.0.0", "2.0.0.0")) should contain only
                route1.setNextHopPortId(port.getId).asJava
        }
    }

    feature("Test router ARP table") {
        scenario("The router mapper creates a unique ARP cache") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1
            val arpCache = obs.getOnNextEvents.get(0).arpCache

            When("The router is updated")
            val router2 = router1.setAdminStateUp(!router1.getAdminStateUp)
            store.update(router2)

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The ARP table should be the same instance")
            (obs.getOnNextEvents.get(1).arpCache eq arpCache) shouldBe true
        }
    }

    feature("Test flow invalidation") {
        scenario("For router") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            And("The flow controller should receive the device invalidation")
            simBackChannel should invalidate (tagForRouter(router.getId))
        }

        scenario("For added route corresponding to destination IP") {
            val obs = createObserver()
            val mapper = testRouterCreated(obs)._2
            val tagManager = obs.getOnNextEvents.get(0).routerMgrTagger

            When("Adding a destination IP")
            tagManager.addIPv4Tag("2.0.0.1", 0)

            When("Adding a route to the router")
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    routerId = Some(mapper.id))
            store.create(route)

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            And("The flow controller should receive the IP invalidation")
            simBackChannel should invalidateForNewRoutes(IPv4Subnet.fromCidr("2.0.0.0/24"))
        }

        scenario("For removed route") {
            val obs = createObserver()
            val router = testRouterCreated(obs)._1

            When("Adding a route to the router")
            val route = createRoute(srcNetwork = "1.0.0.0/24",
                                    dstNetwork = "2.0.0.0/24",
                                    routerId = Some(router.getId))
            store.create(route)

            Then("The observer should receive a router update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The route is deleted")
            store.delete(classOf[TopologyRoute], route.getId)

            And("Waiting for the router updates")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("The flow controller should receive a route invalidation")
            simBackChannel should invalidateForDeletedRoutes(IPv4Subnet.fromCidr("2.0.0.0/24"))
        }
    }

    feature("Test chain updates") {
        scenario("The router receives existing chain") {
            val obs = createObserver()

            Given("A router mapper")
            val routerId = UUID.randomUUID
            val mapper = new RouterMapper(routerId, vt, mutable.Map())

            And("A chain")
            val chain = createChain()
            store.create(chain)

            And("A router with the chain")
            val router = createRouter(id = routerId,
                                      inboundFilterId = Some(chain.getId))

            When("The router is created")
            store.create(router)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the router")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf router

            And("The virtual topology should have prefetched the chain")
            VirtualTopology.tryGet(classOf[Chain], chain.getId) shouldBeDeviceOf chain
        }

        scenario("The router updates when updating chain") {
            val obs = createObserver()

            Given("A router mapper")
            val routerId = UUID.randomUUID
            val mapper = new RouterMapper(routerId, vt, mutable.Map())

            And("A chain")
            val chain1 = createChain()
            store.create(chain1)

            And("A router with the chain")
            val router = createRouter(id = routerId,
                                      inboundFilterId = Some(chain1.getId))

            When("The router is created")
            store.create(router)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the router")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The chain is updated")
            val chain2 = chain1.setName("updated-name").addRouterInboundId(routerId)
            store.update(chain2)

            Then("The observer should receive a second update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf router

            And("The virtual topology should have prefetched the chain")
            VirtualTopology.tryGet(classOf[Chain], chain2.getId) shouldBeDeviceOf chain2
        }

        scenario("The router updates when removing chain") {
            val obs = createObserver()

            Given("A router mapper")
            val routerId = UUID.randomUUID
            val mapper = new RouterMapper(routerId, vt, mutable.Map())

            And("A chain")
            val chain = createChain()
            store.create(chain)

            And("A router with the chain")
            val router1 = createRouter(id = routerId,
                                      inboundFilterId = Some(chain.getId))

            When("The router is created")
            store.create(router1)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the router")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The chain is removed")
            val router2 = router1.clearInboundFilterId()
            store.update(router2)

            Then("The observer should receive a second update")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(2)
            device shouldBeDeviceOf router2

            And("The virtual topology should not have the chain")
            VirtualTopology.tryGet(classOf[Chain], chain.getId)
        }

        scenario("The router receives local redirect chain") {
            val obs = createObserver()

            Given("A router mapper")
            val routerId = UUID.randomUUID
            val mapper = new RouterMapper(routerId, vt, mutable.Map())

            And("A chain")
            val chain = createChain()
            store.create(chain)

            And("A local redirect chain")
            val redirectChain = createChain()
            store.create(redirectChain)

            And("A router with the chain")
            val router = createRouter(id = routerId,
                                      inboundFilterId = Some(chain.getId))
                .toBuilder.setLocalRedirectChainId(redirectChain.getId).build()
            When("The router is created")
            store.create(router)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the router")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf router

            And("The router should have both chain, in correct order")
            device.cfg.inboundFilters.size() shouldBe 2
            device.cfg.inboundFilters.get(0) shouldBe redirectChain.getId.asJava
            device.cfg.inboundFilters.get(1) shouldBe chain.getId.asJava

            And("The virtual topology should have prefetched both chains")
            VirtualTopology.tryGet(classOf[Chain], chain.getId) shouldBeDeviceOf chain
            VirtualTopology.tryGet(classOf[Chain], redirectChain.getId) shouldBeDeviceOf redirectChain
        }

    }

    feature("Test race conditions") {
        scenario("Referenced port missing") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1

            When("Modifying the router by adding a port")
            val router2 = router1.addPortId(UUID.randomUUID())
            store.updateRaw(router2)

            Then("The mapper should not emit a notification")
            obs.getOnNextEvents should have size 1

            When("Modifying the router by changing the admin state")
            val router3 = router1.setAdminStateUp(true)
            store.updateRaw(router3)

            Then("The mapper should emit a notification")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDeviceOf router3
        }

        scenario("Referenced router route missing") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1

            When("Modifying the router by adding a route")
            val router2 = router1.addRouteId(UUID.randomUUID())
            store.updateRaw(router2)

            Then("The mapper should not emit a notification")
            obs.getOnNextEvents should have size 1

            When("Modifying the router by changing the admin state")
            val router3 = router1.setAdminStateUp(true)
            store.updateRaw(router3)

            Then("The mapper should emit a notification")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDeviceOf router3
        }

        scenario("Referenced port route missing") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1

            When("Creating an exterior port")
            val port1 = createExteriorPort(router1.getId)
            store.create(port1)

            And("Modifying the port to include a route")
            val port2 = port1.addRouteId(UUID.randomUUID())
            store.updateRaw(port2)

            Then("The mapper should receive only one notification")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2

            When("Modifying the router by changing the admin state")
            val router2 = router1.setAdminStateUp(true)
            store.updateRaw(router2)

            Then("The mapper should emit a notification")
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBeDeviceOf router2
        }

        scenario("Referenced load balancer missing") {
            val obs = createObserver()
            val router1 = testRouterCreated(obs)._1

            When("Modifying the router by setting the load balancer")
            val router2 = router1.setLoadBalancerId(UUID.randomUUID())
            store.updateRaw(router2)

            Then("The mapper should not emit a notification")
            obs.getOnNextEvents should have size 1

            When("Modifying the router by changing the admin state")
            val router3 = router1.setAdminStateUp(true)
            store.updateRaw(router3)

            Then("The mapper should emit a notification")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDeviceOf router3
        }
    }

    feature("Test mirror updates") {
        scenario("The bridge receives existing mirrors") {
            val obs = createObserver()

            Given("A chain mapper")
            val routerId = UUID.randomUUID
            val mapper = new RouterMapper(routerId, vt, mutable.Map())

            And("Two mirrors")
            val bridge = createBridge()
            val port1 = createBridgePort(bridgeId = Some(bridge.getId))
            val mirror1 = createMirror(toPort = port1.getId)
            val mirror2 = createMirror(toPort = port1.getId)
            store.multi(Seq(CreateOp(bridge), CreateOp(port1), CreateOp(mirror1),
                            CreateOp(mirror2)))

            And("A router with the mirrors")
            val router = createRouter(id = routerId,
                                      inboundMirrorIds = Set(mirror1.getId),
                                      outboundMirrorIds = Set(mirror2.getId))

            When("The router is created")
            store.create(router)

            And("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the router")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf router

            And("The virtual topology should have prefetched the mirrors")
            VirtualTopology.tryGet(classOf[Mirror], mirror1.getId) shouldBeDeviceOf mirror1
            VirtualTopology.tryGet(classOf[Mirror], mirror2.getId) shouldBeDeviceOf mirror2

            When("The first mirror updates to a different port")
            val port2 = createBridgePort(bridgeId = Some(bridge.getId))
            val mirror3 = mirror1.toBuilder
                .setToPortId(port2.getId)
                .addRouterInboundIds(router.getId)
                .build()
            store.multi(Seq(CreateOp(port2), UpdateOp(mirror3)))

            Then("The observer should receive the bridge")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf router

            And("The virtual topology should have prefetched the mirrors")
            VirtualTopology.tryGet(classOf[Mirror], mirror1.getId) shouldBeDeviceOf mirror3
            VirtualTopology.tryGet(classOf[Mirror], mirror2.getId) shouldBeDeviceOf mirror2

            When("The second mirror is deleted")
            store.delete(classOf[TopologyMirror], mirror2.getId)

            Then("The observer should receive the bridge")
            obs.awaitOnNext(3, timeout) shouldBe true
            val device3 = obs.getOnNextEvents.get(2)
            device3 shouldBeDeviceOf router
        }
    }
}
