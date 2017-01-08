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

import scala.collection.JavaConverters._

import org.apache.zookeeper.Op
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.StateTableEncoder.Fip64Encoder.DefaultValue
import org.midonet.cluster.data.storage.model.Fip64Entry
import org.midonet.cluster.data.storage.{CreateOp, InMemoryStorage, Storage}
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPSubnetUtil.fromV4Proto
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppDownlink._
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.packets._
import org.midonet.util.logging.Logging

@RunWith(classOf[JUnitRunner])
class VppDownlinkTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private val downlinkMac = MAC.random()

    private class TestableVppDownlink extends Logging with VppDownlink {
        override def vt = VppDownlinkTest.this.vt
        override def logSource: String = "vpp-downlink"
        val observer = new TestObserver[Notification]
        downlinkObservable subscribe observer

        def add(networkId: UUID): Unit = {
            addNetwork(networkId)
        }

        def remove(networkId: UUID): Unit = {
            removeNetwork(networkId)
        }
    }

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = vt.store
    }

    private def addFip64Entry(networkId: UUID, portId: UUID, routerId: UUID,
                              fixedIp: IPv4Addr = IPv4Addr.random,
                              floatingIp: IPv6Addr = IPv6Addr.random,
                              natPool: IPv4Subnet =
                                  new IPv4Subnet(IPv4Addr.random, 8))
    : Fip64Entry = {
        val table = vt.stateTables
            .getTable[Fip64Entry, AnyRef](classOf[NeutronNetwork], networkId,
                                          MidonetBackend.Fip64Table)
        val entry = Fip64Entry(fixedIp, floatingIp, natPool, portId, routerId)
        table.addPersistent(entry, DefaultValue)
        entry
    }

    private def removeFip64Entry(networkId: UUID, entry: Fip64Entry): Unit = {
        val table = vt.stateTables
            .getTable[Fip64Entry, AnyRef](classOf[NeutronNetwork], networkId,
                                          MidonetBackend.Fip64Table)
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
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val downlinkMac = MAC.random()
            val router = createRouter()
            val network = createNetwork()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(network)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            val entry = addFip64Entry(network.getId, port.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            vpp.observer.getOnNextEvents.get(0) shouldBe createTunnel(
                port, VppVrfs.FirstFree, 1234, downlinkMac)
            vpp.observer.getOnNextEvents.get(1) shouldBe associateFip(
                port, entry, VppVrfs.FirstFree)

            When("Removing a network")
            vpp.remove(network.getId)

            Then("The actor should receive a DisassociateFip and Delete notification")
            vpp.observer.getOnNextEvents should have size 4
            vpp.observer.getOnNextEvents.get(2) shouldBe disassociateFip(
                port, entry, VppVrfs.FirstFree)
            vpp.observer.getOnNextEvents.get(3) shouldBe DeleteTunnel(
                port.getId, VppVrfs.FirstFree, 1234)
        }

        scenario("Port does not exist") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A network")
            val network = createNetwork()
            store.create(network)

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a non-existing port to the downlink table")
            val portId = UUID.randomUUID()
            addFip64Entry(network.getId, portId, UUID.randomUUID())

            Then("The actor should not receive any notification")
            vpp.observer.getOnNextEvents shouldBe empty
        }

        scenario("Port deleted for existing downlink") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1)
            val network = createNetwork()
            store.multi(Seq(CreateOp(router), CreateOp(port),
                            CreateOp(network)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            val entry = addFip64Entry(network.getId, port.getId, router.getId)

            And("Deleting the port")
            store.delete(classOf[Port], port.getId)

            Then("The actor should receive a Delete notification")
            vpp.observer.getOnNextEvents should have size 4
            vpp.observer.getOnNextEvents.get(2) shouldBe disassociateFip(
                port, entry, VppVrfs.FirstFree)
            vpp.observer.getOnNextEvents.get(3) shouldBe DeleteTunnel(
                port.getId, VppVrfs.FirstFree, 1)
        }

        scenario("Multiple downlinks use different VRF numbers") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val downlinkMac1 = MAC.random()
            val router = createRouter()
            val network = createNetwork()
            val port1 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac1,
                                         tunnelKey = 1234)
            store.multi(Seq(CreateOp(router), CreateOp(network),
                            CreateOp(port1)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(network.getId, port1.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            vpp.observer.getOnNextEvents should have size 2
            vpp.observer.getOnNextEvents.get(0) shouldBe createTunnel(
                port1, VppVrfs.FirstFree, 1234, downlinkMac1)
            vpp.observer.getOnNextEvents.get(1) shouldBe associateFip(
                port1, entry1, VppVrfs.FirstFree)

            When("Adding a new port")
            val downlinkMac2 = MAC.random()
            val port2 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac2,
                                         tunnelKey = 1235)
            store.multi(Seq(CreateOp(port2)))
            val entry2 = addFip64Entry(network.getId, port2.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            vpp.observer.getOnNextEvents should have size 4
            vpp.observer.getOnNextEvents.get(2) shouldBe createTunnel(
                port2, VppVrfs.FirstFree + 1, 1235, downlinkMac2)
            vpp.observer.getOnNextEvents.get(3) shouldBe associateFip(
                port2, entry2, VppVrfs.FirstFree+1)

            When("The first port is deleted")
            store.delete(classOf[Port], port1.getId)

            Then("The actor should receive a Delete notification")
            vpp.observer.getOnNextEvents should have size 6
            vpp.observer.getOnNextEvents.get(4) shouldBe disassociateFip(
                port1, entry1, VppVrfs.FirstFree)
            vpp.observer.getOnNextEvents.get(5) shouldBe DeleteTunnel(
                port1.getId, VppVrfs.FirstFree, 1234)

            When("Adding a new port")
            val downlinkMac3 = MAC.random()
            val port3 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac3,
                                         tunnelKey = 1236)
            store.multi(Seq(CreateOp(port3)))
            val entry3 = addFip64Entry(network.getId, port3.getId, router.getId)

            Then("The actor should receive a Create notification with 1 VRF")
            vpp.observer.getOnNextEvents should have size 8
            vpp.observer.getOnNextEvents.get(6) shouldBe createTunnel(
                port3, VppVrfs.FirstFree, 1236, downlinkMac3)
            vpp.observer.getOnNextEvents.get(7) shouldBe associateFip(
                port3, entry3, VppVrfs.FirstFree)
        }

        scenario("Downlink deleted when last FIP removed") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val router = createRouter()
            val network = createNetwork()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            store.multi(Seq(CreateOp(router), CreateOp(network),
                            CreateOp(port)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            val entry = addFip64Entry(network.getId, port.getId, router.getId)

            And("Removing the entry")
            removeFip64Entry(network.getId, entry)

            Then("The actor should receive a DisassociateFip and Delete notification")
            vpp.observer.getOnNextEvents should have size 4
            vpp.observer.getOnNextEvents.get(2) shouldBe disassociateFip(
                port, entry, VppVrfs.FirstFree)
            vpp.observer.getOnNextEvents.get(3) shouldBe DeleteTunnel(
                port.getId, VppVrfs.FirstFree, 1234)
        }

        scenario("Deleted downlink frees VRF numbers") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val router = createRouter()
            val network = createNetwork()
            val downlinkMac = MAC.random()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            store.multi(Seq(CreateOp(router), CreateOp(network),
                            CreateOp(port)))

            When("Adding a network")
            vpp.add(network.getId)

            When("Adding a new port to the downlink table")
            var entry = addFip64Entry(network.getId, port.getId, router.getId)

            And("Removing the entry")
            removeFip64Entry(network.getId, entry)

            And("Addind the same port")
            entry = addFip64Entry(network.getId, port.getId, router.getId)

            Then("The actor should receive 0 VRF")
            vpp.observer.getOnNextEvents should have size 6
            vpp.observer.getOnNextEvents.get(4) shouldBe createTunnel(
                port, VppVrfs.FirstFree, 1234, downlinkMac)
            vpp.observer.getOnNextEvents.get(5) shouldBe associateFip(
                port, entry, VppVrfs.FirstFree)
        }

        scenario("Port updates are filtered") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val router = createRouter()
            val network = createNetwork()
            val port1 = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(network),
                            CreateOp(port1)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            var entry = addFip64Entry(network.getId, port1.getId, router.getId)

            And("The port is updated")
            val port2 = port1.toBuilder
                .setPortMac("01:02:03:04:05:06")
                .build()
            store.update(port2)

            Then("The actor should only receive two notifications")
            vpp.observer.getOnNextEvents should have size 2
        }

        scenario("Multiple networks") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val downlinkMac = MAC.random()
            val router = createRouter()
            val network1 = createNetwork()
            val port1 = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            store.multi(Seq(CreateOp(router), CreateOp(port1), CreateOp(network1)))

            When("Adding a first network")
            vpp.add(network1.getId)

            And("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(network1.getId, port1.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            vpp.observer.getOnNextEvents.get(0) shouldBe createTunnel(
                port1, VppVrfs.FirstFree, 1234, downlinkMac)
            vpp.observer.getOnNextEvents.get(1) shouldBe associateFip(
                port1, entry1, VppVrfs.FirstFree)

            When("Adding a second network")
            val network2 = createNetwork()
            val port2 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac,
                                         tunnelKey = 5678)
            store.multi(Seq(CreateOp(network2), CreateOp(port2)))
            vpp.add(network2.getId)

            And("Adding a new port to the downlink table")
            val entry2 = addFip64Entry(network2.getId, port2.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            vpp.observer.getOnNextEvents.get(2) shouldBe createTunnel(
                port2, VppVrfs.FirstFree + 1, 5678, downlinkMac)
            vpp.observer.getOnNextEvents.get(3) shouldBe associateFip(
                port2, entry2, VppVrfs.FirstFree + 1)

            When("Removing the first network")
            vpp.remove(network1.getId)

            Then("The actor should receive a DisassociateFip and Delete notification")
            vpp.observer.getOnNextEvents.get(4) shouldBe disassociateFip(
                port1, entry1, VppVrfs.FirstFree)
            vpp.observer.getOnNextEvents.get(5) shouldBe DeleteTunnel(
                port1.getId, VppVrfs.FirstFree, 1234)

            When("Removing the second network")
            vpp.remove(network2.getId)

            Then("The actor should receive a DisassociateFip and Delete notification")
            vpp.observer.getOnNextEvents.get(6) shouldBe disassociateFip(
                port2, entry2, VppVrfs.FirstFree + 1)
            vpp.observer.getOnNextEvents.get(7) shouldBe DeleteTunnel(
                port2.getId, VppVrfs.FirstFree + 1, 5678)

            When("Adding the first network again")
            vpp.add(network1.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            vpp.observer.getOnNextEvents.get(2) shouldBe createTunnel(
                port2, VppVrfs.FirstFree + 1, 5678, downlinkMac)
            vpp.observer.getOnNextEvents.get(3) shouldBe associateFip(
                port2, entry2, VppVrfs.FirstFree + 1)
        }
    }

    feature("VPP downlink handles FIP updates") {
        scenario("Adding and removing FIP64 entries") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val router = createRouter()
            val network = createNetwork()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            store.multi(Seq(CreateOp(router), CreateOp(network),
                            CreateOp(port)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(network.getId, port.getId, router.getId)

            And("Adding another entry")
            val entry2 = addFip64Entry(network.getId, port.getId, router.getId)

            Then("The actor should receive AssociateFip notification")
            vpp.observer.getOnNextEvents should have size 3
            vpp.observer.getOnNextEvents.get(2) shouldBe associateFip(
                port, entry2, VppVrfs.FirstFree)

            When("Deleting the first entry")
            removeFip64Entry(network.getId, entry1)

            Then("The actor should receive DisassociateFip notification")
            vpp.observer.getOnNextEvents should have size 4
            vpp.observer.getOnNextEvents.get(3) shouldBe disassociateFip(
                port, entry1, VppVrfs.FirstFree)

            When("Deleting the second entry")
            removeFip64Entry(network.getId, entry2)

            Then("The actor should receive DownlinkDeleted")
            vpp.observer.getOnNextEvents should have size 6
            vpp.observer.getOnNextEvents.get(4) shouldBe disassociateFip(
                port, entry2, VppVrfs.FirstFree)
            vpp.observer.getOnNextEvents.get(5) shouldBe DeleteTunnel(
                port.getId, VppVrfs.FirstFree, 1234)
        }

        scenario("All FIP entries are cleared when port deleted") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val router = createRouter()
            val network = createNetwork()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            store.multi(Seq(CreateOp(router), CreateOp(network),
                            CreateOp(port)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            val entry1 = addFip64Entry(network.getId, port.getId, router.getId)

            And("Adding another entry")
            val entry2 = addFip64Entry(network.getId, port.getId, router.getId)

            And("Adding another entry")
            val entry3 = addFip64Entry(network.getId, port.getId, router.getId)

            And("The port is deleted")
            store.delete(classOf[Port], port.getId)

            Then("All FIPs are disassociated")
            vpp.observer.getOnNextEvents should have size 8
            vpp.observer.getOnNextEvents.asScala.slice(4, 7) should contain allOf(
                disassociateFip(port, entry1, VppVrfs.FirstFree),
                disassociateFip(port, entry2, VppVrfs.FirstFree),
                disassociateFip(port, entry3, VppVrfs.FirstFree))
            vpp.observer.getOnNextEvents.get(7) shouldBe DeleteTunnel(
                port.getId, VppVrfs.FirstFree, 1234)
        }
    }

    feature("VPP downlink handles table completion") {
        scenario("The table is deleted") {
            Given("A VPP downlink instance")
            val vpp = new TestableVppDownlink

            And("A port")
            val router = createRouter()
            val network = createNetwork()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            val store = this.store.asInstanceOf[InMemoryStorage]
            store.multi(Seq(CreateOp(router), CreateOp(network),
                            CreateOp(port)))

            When("Adding a network")
            vpp.add(network.getId)

            And("Adding a new port to the downlink table")
            val entry = addFip64Entry(network.getId, port.getId, router.getId)

            And("Deleting the table")
            val children = store.tablesDirectory.getChildren(
                vt.stateTables.fip64TablePath(network.getId), null)
            val ops = new util.ArrayList[Op]()
            for (child <- children.asScala) {
                ops.add(Op.delete(vt.stateTables.fip64TablePath(network.getId) +
                                  "/" + child, -1))
            }
            ops.add(Op.delete(vt.stateTables.fip64TablePath(network.getId), -1))
            store.tablesDirectory.multi(ops)

            Then("The VPP downlink should cleanup")
            vpp.observer.getOnNextEvents should have size 4
            vpp.observer.getOnNextEvents.get(2) shouldBe disassociateFip(
                port, entry, VppVrfs.FirstFree)
            vpp.observer.getOnNextEvents.get(3) shouldBe DeleteTunnel(
                port.getId, VppVrfs.FirstFree, 1234)
        }
    }
}
