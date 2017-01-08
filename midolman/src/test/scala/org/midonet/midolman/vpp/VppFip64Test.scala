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

import java.util.UUID
import java.util.concurrent.ExecutorService

import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.CreateOp
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.midolman.vpp.VppExternalNetwork.{AddExternalNetwork, RemoveExternalNetwork}
import org.midonet.midolman.vpp.VppProviderRouter.Gateways
import org.midonet.midolman.vpp.VppUplink.{AddUplink, DeleteUplink}
import org.midonet.packets.{IPv6Addr, IPv6Subnet}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService

@RunWith(classOf[JUnitRunner])
class VppFip64Test extends MidolmanSpec with TopologyBuilder {

    private var backend: MidonetBackend = _
    private var vt: VirtualTopology = _

    private class TestableVppFip64 extends VppExecutor with VppFip64 {
        override def vt = VppFip64Test.this.vt
        override def hostId = VppFip64Test.this.hostId
        override def logSource: String = "vpp-fip64"

        var messages = List[Any]()

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
            startFip64()
            notifyStarted()
        }

        protected override def doStop(): Unit = {
            stopFip64()
            super.doStop()
            notifyStopped()
        }
    }

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        backend = injector.getInstance(classOf[MidonetBackend])
    }

    private def createVppFip64(): TestableVppFip64 = {
        val vppFip64 = new TestableVppFip64
        vppFip64.startAsync().awaitRunning()
        vppFip64
    }

    private def randomSubnet6(): IPv6Subnet = {
        new IPv6Subnet(IPv6Addr.random, 64)
    }

    private def addUplink(port: Port, portIds: UUID*): AddUplink = {
        val portSubnet = port.getPortSubnetList.asScala.map(_.asJava).collect {
            case subnet: IPv6Subnet => subnet
        }.head
        AddUplink(port.getId.asJava, port.getRouterId.asJava, portSubnet,
                  portIds.asJava)
    }

    feature("VPP FIP64 handles uplinks") {
        scenario("Uplink added and removed") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port, port.getId.asJava),
                Gateways(port.getId.asJava, Set()))

            When("The port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should delete the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port, port.getId.asJava),
                Gateways(port.getId.asJava, Set()),
                DeleteUplink(port.getId.asJava))
        }

        scenario("Multiple uplink ports for the same provider router") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("Two ports for the same router")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1),
                                    CreateOp(port2)))

            When("The first port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()))

            When("The second port becomes active")
            VirtualToPhysicalMapper.setPortActive(port2.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()),
                addUplink(port2, port2.getId.asJava),
                Gateways(port2.getId.asJava, Set()))

            When("The first port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should remove the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()),
                addUplink(port2, port2.getId.asJava),
                Gateways(port2.getId.asJava, Set()),
                DeleteUplink(port1.getId.asJava))

            When("The second port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(port2.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should remove the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()),
                addUplink(port2, port2.getId.asJava),
                Gateways(port2.getId.asJava, Set()),
                DeleteUplink(port1.getId.asJava),
                DeleteUplink(port2.getId.asJava))
        }

        scenario("Multiple uplink ports for the different provider routers") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("Two ports for different routers")
            val router1 = createRouter()
            val router2 = createRouter()
            val port1 = createRouterPort(routerId = Some(router1.getId.asJava),
                                         portSubnet = randomSubnet6())
            val port2 = createRouterPort(routerId = Some(router2.getId.asJava),
                                         portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router1), CreateOp(port1),
                                    CreateOp(router2), CreateOp(port2)))

            When("The first port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()))

            When("The second port becomes active")
            VirtualToPhysicalMapper.setPortActive(port2.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()),
                addUplink(port2, port2.getId.asJava),
                Gateways(port2.getId.asJava, Set()))

            When("The first port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should remove the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()),
                addUplink(port2, port2.getId.asJava),
                Gateways(port2.getId.asJava, Set()),
                DeleteUplink(port1.getId.asJava))

            When("The second port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(port2.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should remove the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                Gateways(port1.getId.asJava, Set()),
                addUplink(port2, port2.getId.asJava),
                Gateways(port2.getId.asJava, Set()),
                DeleteUplink(port1.getId.asJava),
                DeleteUplink(port2.getId.asJava))
        }

        scenario("Cleanup notifications on stop") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsAs Seq(
                addUplink(port, port.getId.asJava),
                Gateways(port.getId.asJava, Set()))

            When("The instance is stopped")
            vpp.stopAsync().awaitTerminated()

            Then("The controller should delete the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port, port.getId.asJava),
                Gateways(port.getId.asJava, Set()),
                DeleteUplink(port.getId.asJava))
        }
    }

    feature("VPP FIP64 handles external networks") {
        scenario("Uplink port becomes active and inactive") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("A port")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId.asJava),
                                              portSubnet = randomSubnet6())
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId.asJava),
                                              peerId = Some(routerPort.getId.asJava))
            val network = createNetwork(id = bridge.getId.asJava,
                                        external = Some(true))
            backend.store.multi(Seq(CreateOp(router), CreateOp(bridge),
                                    CreateOp(routerPort), CreateOp(bridgePort),
                                    CreateOp(network)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(routerPort.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink and external network")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()),
                AddExternalNetwork(bridge.getId.asJava))

            When("The port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(routerPort.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should remove the uplink and external network")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()),
                AddExternalNetwork(bridge.getId.asJava),
                RemoveExternalNetwork(bridge.getId.asJava),
                DeleteUplink(routerPort.getId.asJava))
        }

        scenario("Provider router connects to external network") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("A port")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId.asJava),
                                              portSubnet = randomSubnet6())
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId.asJava),
                                              peerId = Some(routerPort.getId.asJava))
            val network = createNetwork(id = bridge.getId.asJava,
                                        external = Some(true))
            backend.store.multi(Seq(CreateOp(router), CreateOp(routerPort)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(routerPort.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()))

            When("The provider router connects to the external network")
            backend.store.multi(Seq(CreateOp(bridge), CreateOp(bridgePort),
                                    CreateOp(network)))

            Then("The controller should add the external network")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()),
                AddExternalNetwork(bridge.getId.asJava))

            When("The provider router disconnects from the external network")
            backend.store.delete(classOf[Port], bridgePort.getId)

            Then("The controller should remove the external network")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()),
                AddExternalNetwork(bridge.getId.asJava),
                RemoveExternalNetwork(bridge.getId.asJava))
        }

        scenario("Network changes to external") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("A port")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId.asJava),
                                              portSubnet = randomSubnet6())
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId.asJava),
                                              peerId = Some(routerPort.getId.asJava))
            val network1 = createNetwork(id = bridge.getId.asJava,
                                        external = Some(false))
            backend.store.multi(Seq(CreateOp(router), CreateOp(bridge),
                                    CreateOp(routerPort), CreateOp(bridgePort),
                                    CreateOp(network1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(routerPort.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()))

            When("The network is set as external")
            val network2 = network1.toBuilder.setExternal(true).build()
            backend.store.update(network2)

            Then("The controller should add the external network")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()),
                AddExternalNetwork(bridge.getId.asJava))

            When("The network is unset as external")
            backend.store.update(network1)

            Then("The controller should remove the external network")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(routerPort, routerPort.getId.asJava),
                Gateways(routerPort.getId.asJava, Set()),
                AddExternalNetwork(bridge.getId.asJava),
                RemoveExternalNetwork(bridge.getId.asJava))
        }
    }

}
