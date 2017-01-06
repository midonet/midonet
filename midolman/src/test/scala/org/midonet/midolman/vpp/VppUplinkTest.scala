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

import scala.collection.JavaConverters._

import com.google.common.util.concurrent.AbstractService

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.CreateOp
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.midolman.vpp.VppUplink.{AddUplink, DeleteUplink}
import org.midonet.packets.{IPv6Addr, IPv6Subnet}
import org.midonet.util.functors.makeAction1
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class VppUplinkTest extends MidolmanSpec with TopologyBuilder {

    private var backend: MidonetBackend = _
    private var vt: VirtualTopology = _

    private class TestableVppUplink extends AbstractService with VppUplink {
        override def vt = VppUplinkTest.this.vt
        override val log = Logger(LoggerFactory.getLogger("vpp-uplink"))
        var messages = List[Any]()

        uplinkObservable.subscribe(makeAction1[Notification] { m =>
            log debug s"Received message $m"
            messages = messages :+ m
        })

        protected override def doStart(): Unit = {
            startUplink()
            notifyStarted()
        }

        protected override def doStop(): Unit = {
            stopUplink()
            notifyStopped()
        }
    }

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        backend = injector.getInstance(classOf[MidonetBackend])
    }

    private def createVppUplink(): TestableVppUplink = {
        val vppUplink = new TestableVppUplink
        vppUplink.startAsync().awaitRunning()
        vppUplink
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

    feature("VPP uplink handles uplink updates") {
        scenario("Port without IPv6 address becomes active") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should not receive any notification")
            vpp.messages shouldBe empty
        }

        scenario("Non-router ports are ignored") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val bridge = createBridge()
            val port = createBridgePort(bridgeId = Some(bridge.getId.asJava))
            backend.store.multi(Seq(CreateOp(bridge), CreateOp(port)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should ignore the port")
            vpp.messages shouldBe empty
        }

        scenario("Port with IPv6 address becomes active and inactive") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain only addUplink(port, port.getId.asJava)

            When("The port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should delete the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port, port.getId.asJava),
                DeleteUplink(port.getId.asJava))
        }

        scenario("Port with IPv6 addresses is deleted") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0)

            And("The port is deleted")
            backend.store.delete(classOf[Port], port.getId)

            Then("The controller should add and delete the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port, port.getId.asJava),
                DeleteUplink(port.getId.asJava))
        }

        scenario("Port adds an IPv6 address") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            And("The port adds an IPv6 address")
            val port2 =
                port1.toBuilder.addPortSubnet(randomSubnet6().asProto).build()
            backend.store.update(port2)

            Then("The controller should add the uplink")
            vpp.messages should contain only addUplink(port2, port2.getId.asJava)
        }

        scenario("Port removes the IPv6 address") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava))
            val port2 =
                port1.toBuilder.addPortSubnet(randomSubnet6().asProto).build()
            backend.store.multi(Seq(CreateOp(router), CreateOp(port2)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain only addUplink(port2, port2.getId.asJava)

            When("The port removes the IPv6 address")
            backend.store.update(port1)

            Then("The controller should delete the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port2, port2.getId.asJava),
                DeleteUplink(port1.getId.asJava))
        }

        scenario("Port updates the IPv6 address") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)

            When("Updating the port address")
            val port2 = createRouterPort(id = port1.getId.asJava,
                                         routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            backend.store.update(port2)

            Then("The controller should add the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                DeleteUplink(port2.getId.asJava),
                addUplink(port2, port2.getId.asJava))
        }

        scenario("Uplinks deleted on stop") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            val port3 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1),
                                    CreateOp(port2), CreateOp(port3)))

            When("The ports become active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)
            VirtualToPhysicalMapper.setPortActive(port2.getId.asJava, 0,
                                                  active = true, 0)
            VirtualToPhysicalMapper.setPortActive(port3.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplinks")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                addUplink(port2, port2.getId.asJava),
                addUplink(port3, port3.getId.asJava))

            When("Stopping the controller")
            vpp.stopAsync().awaitTerminated()

            Then("The controller should delete the uplinks")
            vpp.messages should contain theSameElementsAs Seq(
                addUplink(port1, port1.getId.asJava),
                addUplink(port2, port2.getId.asJava),
                addUplink(port3, port3.getId.asJava),
                DeleteUplink(port1.getId.asJava),
                DeleteUplink(port2.getId.asJava),
                DeleteUplink(port3.getId.asJava))
        }

        scenario("Corrupted ports are deleted") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)

            When("The port becomes corrupted")
            val port2 = port1.toBuilder.clearPortSubnet().addPortSubnet(
                Commons.IPSubnet.newBuilder()
                    .setVersion(Commons.IPVersion.V6)
                    .setAddress("invalid")
                    .setPrefixLength(0)).build()
            backend.store.update(port2)

            Then("The controller should delete the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                DeleteUplink(port1.getId.asJava))
        }

        scenario("Non-relevant port updates are ignored") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)

            When("The port is updated")
            val port2 = port1.toBuilder.setTunnelKey(5L).build()
            backend.store.update(port2)

            Then("The controller should not receive another notification")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)
        }
    }

    feature("VPP uplink handles port group membership") {
        scenario("Controller receives stateful port group ports") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port with a port group")
            val router = createRouter()
            val portGroup = createPortGroup(stateful = Some(true))
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6(),
                                         portGroupIds = Set(portGroup.getId.asJava))
            backend.store.multi(Seq(CreateOp(router), CreateOp(portGroup),
                                    CreateOp(port1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should report the ports in the group")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)

            When("Adding a port to the port group")
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portGroupIds = Set(portGroup.getId.asJava))
            backend.store.create(port2)

            Then("The controller should report the ports in the group")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                DeleteUplink(port1.getId.asJava),
                addUplink(port1, port1.getId.asJava, port2.getId.asJava))

            When("Removing a port from the port group")
            backend.store.delete(classOf[Port], port2.getId)

            Then("The controller should report the ports in the group")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port1, port1.getId.asJava),
                DeleteUplink(port1.getId.asJava),
                addUplink(port1, port1.getId.asJava, port2.getId.asJava),
                DeleteUplink(port1.getId.asJava),
                addUplink(port1, port1.getId.asJava))
        }

        scenario("Controller ignores stateless port groups") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port with a port group")
            val router = createRouter()
            val portGroup = createPortGroup(stateful = Some(false))
            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6(),
                                         portGroupIds = Set(portGroup.getId.asJava))
            backend.store.multi(Seq(CreateOp(router), CreateOp(portGroup),
                                    CreateOp(port1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should not report the ports in the group")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)

            When("Adding a port to the port group")
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portGroupIds = Set(portGroup.getId.asJava))
            backend.store.create(port2)

            Then("The controller should not report the ports in the group")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)
        }

        scenario("Controller aggregates multiple port groups") {
            Given("A VPP uplink instance")
            val vpp = createVppUplink()

            And("A port with a port group")
            val router = createRouter()
            val portGroup1 = createPortGroup(stateful = Some(true))
            val portGroup2 = createPortGroup(stateful = Some(true))

            val port1 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portSubnet = randomSubnet6(),
                                         portGroupIds = Set(portGroup1.getId.asJava,
                                                            portGroup2.getId.asJava))
            backend.store.multi(Seq(CreateOp(router), CreateOp(portGroup1),
                                    CreateOp(portGroup2), CreateOp(port1)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port1.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should report the ports in the group")
            vpp.messages should contain only addUplink(port1, port1.getId.asJava)

            When("Adding a port to one port group")
            val port2 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portGroupIds = Set(portGroup1.getId.asJava))

            And("Adding a port to both port groups")
            val port3 = createRouterPort(routerId = Some(router.getId.asJava),
                                         portGroupIds = Set(portGroup1.getId.asJava,
                                                            portGroup2.getId.asJava))
            backend.store.multi(Seq(CreateOp(port2), CreateOp(port3)))

            Then("The controller should report all ports")
            vpp.messages(1) shouldBe DeleteUplink(port1.getId.asJava)
            val add = vpp.messages(2).asInstanceOf[AddUplink]
            add.uplinkPortIds should contain theSameElementsAs Seq(
                port1.getId.asJava, port2.getId.asJava, port3.getId.asJava)
        }
    }
}
