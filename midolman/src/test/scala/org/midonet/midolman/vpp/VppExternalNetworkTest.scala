/*
 * Copyright 2017 Midokura SARL
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
import java.util.{Collections, UUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{CreateOp, Storage}
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppExternalNetwork.{AddExternalNetwork, RemoveExternalNetwork}
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.midolman.vpp.VppProviderRouter.ProviderRouter
import org.midonet.packets.IPv6Subnet
import org.midonet.util.logging.Logging

@RunWith(classOf[JUnitRunner])
class VppExternalNetworkTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

    private class TestableExternalNetwork extends Logging
                                                  with VppExternalNetwork {

        override def vt = VppExternalNetworkTest.this.vt
        override def logSource = "vpp-external-net"

        val observer = new TestObserver[Notification]
        externalNetworkObservable subscribe observer

        def update(router: ProviderRouter): Unit = {
            updateProviderRouter(router)
        }
    }

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = vt.backend.store
    }

    feature("External networks instance manages updates") {
        scenario("Provider router already connected to external network") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network = createNetwork(id = bridge.getId,
                                        external = Some(true))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network)))

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort.getId)))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("Removing the provider router")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }

        scenario("Network is marked as external") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network1 = createNetwork(id = bridge.getId,
                                        external = Some(false))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network1)))

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort.getId)))

            Then("The observer should not receive a notification")
            vpp.observer.getOnNextEvents shouldBe empty

            When("The network is marked as external")
            val network2 = network1.toBuilder.setExternal(true).build()
            store.update(network2)

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("The network is unmarked as external")
            store.update(network1)

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }

        scenario("Provider router has multiple ports to an external network") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort1 = createRouterPort(routerId = Some(router.getId))
            val bridgePort1 = createBridgePort(bridgeId = Some(bridge.getId),
                                               peerId = Some(routerPort1.getId))
            val routerPort2 = createRouterPort(routerId = Some(router.getId))
            val bridgePort2 = createBridgePort(bridgeId = Some(bridge.getId),
                                               peerId = Some(routerPort2.getId))
            val network = createNetwork(id = bridge.getId,
                                        external = Some(true))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort1), CreateOp(bridgePort1),
                            CreateOp(routerPort2), CreateOp(bridgePort2),
                            CreateOp(network)))

            When("Adding the provider router")
            val map = new util.HashMap[UUID, UUID]()
            map.put(routerPort1.getId, bridgePort1.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("Adding another provider router port")
            map.put(routerPort2.getId, bridgePort2.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should not receive another notification")
            vpp.observer.getOnNextEvents should have size 1

            When("Removing a provider router port")
            map.remove(routerPort1.getId.asJava)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should not receive another notification")
            vpp.observer.getOnNextEvents should have size 1

            When("Removing the second provider router port")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }

        scenario("Provider router has multiple port to multiple networks") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge1 = createBridge()
            val routerPort1 = createRouterPort(routerId = Some(router.getId))
            val bridgePort1 = createBridgePort(bridgeId = Some(bridge1.getId),
                                               peerId = Some(routerPort1.getId))
            val bridge2 = createBridge()
            val routerPort2 = createRouterPort(routerId = Some(router.getId))
            val bridgePort2 = createBridgePort(bridgeId = Some(bridge2.getId),
                                               peerId = Some(routerPort2.getId))
            val network1 = createNetwork(id = bridge1.getId,
                                         external = Some(true))
            val network2 = createNetwork(id = bridge2.getId,
                                         external = Some(true))
            store.multi(Seq(CreateOp(router),
                            CreateOp(bridge1), CreateOp(bridge2),
                            CreateOp(routerPort1), CreateOp(bridgePort1),
                            CreateOp(routerPort2), CreateOp(bridgePort2),
                            CreateOp(network1), CreateOp(network2)))

            When("Adding the provider router with the first port")
            val map = new util.HashMap[UUID, UUID]()
            map.put(routerPort1.getId, bridgePort1.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the first external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge1.getId, Seq[IPv6Subnet]())

            When("Adding the provider router with both ports")
            map.put(routerPort2.getId, bridgePort2.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the second external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe AddExternalNetwork(
                bridge2.getId, Seq[IPv6Subnet]())

            When("Removing the first provider router port")
            map.remove(routerPort1.getId.asJava)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should remove the first external network")
            vpp.observer.getOnNextEvents.get(2) shouldBe RemoveExternalNetwork(
                bridge1.getId)

            When("Removing the second provider router port")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the second external network")
            vpp.observer.getOnNextEvents.get(3) shouldBe RemoveExternalNetwork(
                bridge2.getId)
        }

        scenario("Provider router connected to non-Neutron network") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge1 = createBridge()
            val routerPort1 = createRouterPort(routerId = Some(router.getId))
            val bridgePort1 = createBridgePort(bridgeId = Some(bridge1.getId),
                                               peerId = Some(routerPort1.getId))
            val bridge2 = createBridge()
            val routerPort2 = createRouterPort(routerId = Some(router.getId))
            val bridgePort2 = createBridgePort(bridgeId = Some(bridge2.getId),
                                               peerId = Some(routerPort2.getId))
            val network2 = createNetwork(id = bridge2.getId,
                                         external = Some(true))
            store.multi(Seq(CreateOp(router),
                            CreateOp(bridge1), CreateOp(bridge2),
                            CreateOp(routerPort1), CreateOp(bridgePort1),
                            CreateOp(routerPort2), CreateOp(bridgePort2),
                            CreateOp(network2)))

            When("Adding the provider router with the first port")
            val map = new util.HashMap[UUID, UUID]()
            map.put(routerPort1.getId, bridgePort1.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should not receive a notification")
            vpp.observer.getOnNextEvents shouldBe empty

            When("Adding the provider router with both ports")
            map.put(routerPort2.getId, bridgePort2.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the second external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge2.getId, Seq[IPv6Subnet]())
        }

        scenario("Additional network updates are ignored") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network1 = createNetwork(id = bridge.getId,
                                        external = Some(true))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network1)))

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort.getId)))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("Updating the network")
            val network2 = network1.toBuilder.setName("network").build()
            store.update(network2)

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents should have size 1
        }
    }
    feature("External networks instance manages subnets") {
        scenario("Provider router already connected to external " +
                 "network with one IPv6 subnet") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network = createNetwork(id = bridge.getId,
                                        external = Some(true))
            val subnet = createBridge()
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network)))

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort
                                                                   .getId)))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("Removing the provider router")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }
    }

}
