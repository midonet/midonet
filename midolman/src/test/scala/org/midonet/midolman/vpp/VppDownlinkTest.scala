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
import java.util.UUID
import java.util.concurrent.ExecutorService

import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.apache.zookeeper.Op
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.StateTableEncoder.Fip64Encoder.DefaultValue
import org.midonet.cluster.data.storage.model.Fip64Entry
import org.midonet.cluster.data.storage.{CreateOp, InMemoryStorage, StateTable}
import org.midonet.cluster.models.Topology.{Port, Rule}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPSubnetUtil.{fromV4Proto, fromV6Proto}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppDownlink._
import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr, IPv6Subnet, MAC}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class VppDownlinkTest extends MidolmanSpec with TopologyBuilder {


    private var backend: MidonetBackend = _
    private var vt: VirtualTopology = _
    private var table: StateTable[Fip64Entry, AnyRef] = _
    private val downlinkMac = MAC.random()

    private class TestableVppDownlink extends VppExecutor with VppDownlink {
        override def vt = VppDownlinkTest.this.vt
        override val log = Logger(LoggerFactory.getLogger("vpp-downlink"))
        var messages = List[Any]()

        protected override def newExecutor: ExecutorService = {
            new SameThreadButAfterExecutorService
        }

        override def receive: Receive = {
            case m =>
                log debug s"Received message $m"
                messages = messages :+ m
                Future.successful(Unit)
        }

        def start(): Unit = startDownlink()

        def stop(): Unit = stopDownlink()

        override def doStart(): Unit = {
            startDownlink()
            notifyStarted()
        }

        override def doStop(): Unit = {
            stopDownlink()
            super.doStop()
            notifyStopped()
        }
    }

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        backend = injector.getInstance(classOf[MidonetBackend])
        table = backend.stateTableStore
            .getTable[Fip64Entry, AnyRef](MidonetBackend.Fip64Table)
    }

    private def createVppDownlink(): TestableVppDownlink = {
        val vppDownlink = new TestableVppDownlink
        vppDownlink.startAsync().awaitRunning()
        vppDownlink
    }

    private def addFip64Entry(portId: UUID, routerId: UUID,
                              fixedIp: IPv4Addr = IPv4Addr.random,
                              floatingIp: IPv6Addr = IPv6Addr.random,
                              natPool: IPv4Subnet =
                                  new IPv4Subnet(IPv4Addr.random, 8))
    : Fip64Entry = {
        val entry = Fip64Entry(fixedIp, floatingIp, natPool, portId, routerId)
        table.addPersistent(entry, DefaultValue)
        entry
    }

    private def removeFip64Entry(entry: Fip64Entry): Unit = {
        table.removePersistent(entry, DefaultValue)
    }

    private def randomSubnet6(): IPv6Subnet = {
        new IPv6Subnet(IPv6Addr.random, 64)
    }

    private def createTunnel(port: Port, vrf: Int, vni: Int, downlinkMac: MAC)
    : CreateTunnel = {
        CreateTunnel(
            portId = port.getId,
            vrfTable = vrf,
            vni = vni,
            routerPortMac=downlinkMac)
    }

    private def associateFip(port: Port, entry: Fip64Entry, vrf: Int)
    : AssociateFip = {
        AssociateFip(portId = port.getId,
                     vrfTable = vrf,
                     vni = port.getTunnelKey.toInt,
                     floatingIp = entry.floatingIp,
                     fixedIp = entry.fixedIp,
                     localIp = fromV4Proto(port.getPortSubnet(0)),
                     natPool = entry.natPool)
    }

    private def disassociateFip(port: Port, entry: Fip64Entry, vrf: Int)
    : DisassociateFip = {
        DisassociateFip(portId = port.getId,
                        vrfTable = vrf,
                        floatingIp = entry.floatingIp,
                        fixedIp = entry.fixedIp,
                        localIp = fromV4Proto(port.getPortSubnet(0)))
    }

    feature("VPP downlink handles downlink updates") {
        scenario("Port with NAT64 create after actor started") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val downlinkMac = MAC.random()
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            val entry = addFip64Entry(port.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            actor.messages should have size 2
            actor.messages.head shouldBe createTunnel(port, VppVrfs.FirstFree, 1234,
                                                      downlinkMac)
            actor.messages(1) shouldBe associateFip(port, entry, VppVrfs.FirstFree)
        }

        scenario("Port does not exist") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            When("Adding a non-existing port to the downlink table")
            val portId = UUID.randomUUID()
            addFip64Entry(portId, UUID.randomUUID())

            Then("The actor should not receive any notification")
            actor.messages shouldBe empty
        }

        scenario("Port deleted for existing downlink") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            val entry = addFip64Entry(port.getId, router.getId)

            And("Deleting the port")
            backend.store.delete(classOf[Port], port.getId)

            Then("The actor should receive a Delete notification")
            actor.messages should have size 4
            actor.messages(2) shouldBe disassociateFip(port, entry, VppVrfs.FirstFree)
            actor.messages(3) shouldBe DeleteTunnel(port.getId, VppVrfs.FirstFree, 1)
        }

        scenario("Multiple downlinks use different VRF numbers") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val downlinkMac1 = MAC.random()
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac1,
                                         tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1)))

            When("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(port1.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            actor.messages should have size 2
            actor.messages.head shouldBe createTunnel(port1, VppVrfs.FirstFree, 1234,
                                                      downlinkMac1)
            actor.messages(1) shouldBe associateFip(port1, entry1, VppVrfs.FirstFree)

            When("Adding a new port")
            val downlinkMac2 = MAC.random()
            val port2 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac2,
                                         tunnelKey = 1235)
            backend.store.multi(Seq(CreateOp(port2)))
            val entry2 = addFip64Entry(port2.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            actor.messages should have size 4
            actor.messages(2) shouldBe createTunnel(port2, VppVrfs.FirstFree+1, 1235,
                                                    downlinkMac2)
            actor.messages(3) shouldBe associateFip(port2, entry2, VppVrfs.FirstFree+1)

            When("The first port is deleted")
            backend.store.delete(classOf[Port], port1.getId)

            Then("The actor should receive a Delete notification")
            actor.messages should have size 6
            actor.messages(4) shouldBe disassociateFip(port1, entry1, VppVrfs.FirstFree)
            actor.messages(5) shouldBe DeleteTunnel(port1.getId, VppVrfs.FirstFree, 1234)

            When("Adding a new port")
            val downlinkMac3 = MAC.random()
            val port3 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac3,
                                         tunnelKey = 1236)
            backend.store.multi(Seq(CreateOp(port3)))
            val entry3 = addFip64Entry(port3.getId, router.getId)

            Then("The actor should receive a Create notification with 1 VRF")
            actor.messages should have size 8
            actor.messages(6) shouldBe createTunnel(port3, VppVrfs.FirstFree, 1236,
                                                    downlinkMac3)
            actor.messages(7) shouldBe associateFip(port3, entry3, VppVrfs.FirstFree)
        }

        scenario("Downlink deleted when last FIP removed") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            val entry = addFip64Entry(port.getId, router.getId)

            And("Removing the entry")
            removeFip64Entry(entry)

            Then("The actor should receive a DisassociateFip and Delete notification")
            actor.messages should have size 4
            actor.messages(2) shouldBe disassociateFip(port, entry, VppVrfs.FirstFree)
            actor.messages(3) shouldBe DeleteTunnel(port.getId, VppVrfs.FirstFree, 1234)
        }

        scenario("Deleted downlink frees VRF numbers") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val downlinkMac = MAC.random()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            var entry = addFip64Entry(port.getId, router.getId)

            And("Removing the entry")
            removeFip64Entry(entry)

            And("Addind the same port")
            entry = addFip64Entry(port.getId, router.getId)

            Then("The actor should receive 0 VRF")
            actor.messages should have size 6
            actor.messages(4) shouldBe createTunnel(port,
                                                    VppVrfs.FirstFree, 1234,
                                                    downlinkMac)
            actor.messages(5) shouldBe associateFip(port, entry,
                                                    VppVrfs.FirstFree)
        }

        scenario("Port updates are filtered") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1)))

            When("Adding a new port to the downlink table")
            var entry = addFip64Entry(port1.getId, router.getId)

            And("The port is updated")
            val port2 = port1.toBuilder
                .setPortMac("01:02:03:04:05:06")
                .build()
            backend.store.update(port2)

            Then("The actor should only receive two notifications")
            actor.messages should have size 2
        }
    }

    feature("VPP downlink handles FIP updates") {
        scenario("Adding and removing FIP64 entries") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(port.getId, router.getId)

            And("Adding another entry")
            val entry2 = addFip64Entry(port.getId, router.getId)

            Then("The actor should receive AssociateFip notification")
            actor.messages should have size 3
            actor.messages(2) shouldBe associateFip(port, entry2, VppVrfs.FirstFree)

            When("Deleting the first entry")
            removeFip64Entry(entry1)

            Then("The actor should receive DisassociateFip notification")
            actor.messages should have size 4
            actor.messages(3) shouldBe disassociateFip(port, entry1, VppVrfs.FirstFree)

            When("Deleting the second entry")
            removeFip64Entry(entry2)

            Then("The actor should receive DownlinkDeleted")
            actor.messages should have size 6
            actor.messages(4) shouldBe disassociateFip(port, entry2, VppVrfs.FirstFree)
            actor.messages(5) shouldBe DeleteTunnel(port.getId, VppVrfs.FirstFree, 1234)
        }

        scenario("All FIP entries are cleared when port deleted") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(port.getId, router.getId)

            And("Adding another entry")
            val entry2 = addFip64Entry(port.getId, router.getId)

            And("Adding another entry")
            val entry3 = addFip64Entry(port.getId, router.getId)

            And("The port is deleted")
            backend.store.delete(classOf[Port], port.getId)

            Then("All FIPs are disassociated")
            actor.messages should have size 8
            actor.messages.slice(4, 7) should contain allOf(
                disassociateFip(port, entry1, VppVrfs.FirstFree),
                disassociateFip(port, entry2, VppVrfs.FirstFree),
                disassociateFip(port, entry3, VppVrfs.FirstFree))
            actor.messages(7) shouldBe DeleteTunnel(port.getId, VppVrfs.FirstFree, 1234)
        }
    }

    feature("VPP downlink handles table completion") {
        scenario("The table is deleted") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            val entry = addFip64Entry(port.getId, router.getId)

            And("Deleting the table")
            val store = backend.stateTableStore.asInstanceOf[InMemoryStorage]
            val children = store.tablesDirectory.getChildren(
                backend.stateTableStore.fip64TablePath, null)
            val ops = new util.ArrayList[Op]()
            for (child <- children.asScala) {
                ops.add(Op.delete(backend.stateTableStore.fip64TablePath + "/" +
                                  child, -1))
            }
            ops.add(Op.delete(backend.stateTableStore.fip64TablePath, -1))
            store.tablesDirectory.multi(ops)

            Then("The VPP downlink should cleanup")
            actor.messages should have size 4
            actor.messages(2) shouldBe disassociateFip(port, entry, VppVrfs.FirstFree)
            actor.messages(3) shouldBe DeleteTunnel(port.getId, VppVrfs.FirstFree, 1234)
        }
    }

    feature("VPP downlink lifecycle") {
        scenario("Messages are not received after stop") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Stopping the downlink")
            actor.stop()

            And("Adding a new port to the downlink table")
            addFip64Entry(port.getId, router.getId)

            Then("The VPP downlink should not receive any notification")
            actor.messages shouldBe empty
        }

        scenario("Messages are received after restart") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val downlinkMac = MAC.random()
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Stopping the downlink")
            actor.stop()

            And("Adding a new port to the downlink table")
            val entry = addFip64Entry(port.getId, router.getId)

            And("Starting the downlink again")
            actor.start()

            Then("The actor should not receive a Create notification")
            actor.messages should have size 2
            actor.messages.head shouldBe createTunnel(port, VppVrfs.FirstFree, 1234,
                                                      downlinkMac)
            actor.messages(1) shouldBe associateFip(port, entry, VppVrfs.FirstFree)
        }

        scenario("State is cleaned at stop") {
            Given("A VPP downlink actor")
            val actor = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1)))

            When("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(port1.getId, router.getId)

            And("Stopping the downlink")
            actor.stop()

            And("Removing the first entry")
            removeFip64Entry(entry1)

            And("Creating a new port")
            val downlinkMac = MAC.random()
            val port2 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac,
                                         tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(port2)))

            And("Adding the second port to the downlink table")
            val entry2 = addFip64Entry(port2.getId, router.getId)

            And("Starting the downlink")
            actor.start()

            Then("The actor should only receive the second port")
            actor.messages should have size 6
            actor.messages(4) shouldBe createTunnel(port2, VppVrfs.FirstFree, 1234,
                                                    downlinkMac)
            actor.messages(5) shouldBe associateFip(port2, entry2, VppVrfs.FirstFree)
        }
    }
}
