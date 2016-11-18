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
import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.gracefulStop
import akka.testkit.TestActorRef

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
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.midolman.vpp.VppDownlink._
import org.midonet.packets.{IPv4Addr, IPv6Addr, IPv6Subnet, MAC}
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class VppDownlinkTest extends MidolmanSpec with TopologyBuilder {


    private var backend: MidonetBackend = _
    private var vt: VirtualTopology = _
    private var table: StateTable[Fip64Entry, AnyRef] = _
    private val downlinkMac = MAC.random()

    private class TestableVppDownlink extends Actor with VppDownlink {
        override def vt = VppDownlinkTest.this.vt
        override val log = Logger(LoggerFactory.getLogger("vpp-downlink"))

        override def receive: Receive = {
            case m => log debug s"Received message $m"
        }

        def start(): Unit = startDownlink()

        def stop(): Unit = stopDownlink()

        override def preStart(): Unit = {
            super.preStart()
            startDownlink()
        }

        override def postStop(): Unit = {
            stopDownlink()
            super.postStop()
        }
    }

    private type TestableVppDownlinkActor = TestableVppDownlink with MessageAccumulator

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        backend = injector.getInstance(classOf[MidonetBackend])
        table = backend.stateTableStore
            .getTable[Fip64Entry, AnyRef](MidonetBackend.Fip64Table)
    }

    private def createVppDownlink(): (TestableVppDownlinkActor, ActorRef) = {
        val ref = TestActorRef[TestableVppDownlinkActor](
            Props(new TestableVppDownlink with MessageAccumulator))
        val actor = ref.underlyingActor
        (actor, ref)
    }

    private def addEntry(portId: UUID, routerId: UUID,
                         fixedIp: IPv4Addr = IPv4Addr.random,
                         floatingIp: IPv6Addr = IPv6Addr.random)
    : Fip64Entry = {
        val entry = Fip64Entry(fixedIp, floatingIp, portId, routerId)
        table.addPersistent(entry, DefaultValue)
        entry
    }

    private def removeEntry(entry: Fip64Entry): Unit = {
        table.removePersistent(entry, DefaultValue)
    }

    private def randomSubnet6(): IPv6Subnet = {
        new IPv6Subnet(IPv6Addr.random, 64)
    }

    private def createDownlink(port: Port, rule: Rule, vrf: Int, vni: Int,
                               downlinkMac: MAC)
    : CreateDownlink = {
        CreateDownlink(
            portId = port.getId,
            vrfTable = vrf,
            vni = vni,
            portAddress4 = fromV4Proto(port.getPortSubnet(0)),
            portAddress6 = fromV6Proto(rule.getNat64RuleData.getPortAddress),
            natPool = ZoomConvert.fromProto(rule.getNat64RuleData.getNatPool,
                                            classOf[NatTarget]),
            routerPortMac=downlinkMac)
    }

    private def updateDownlink(port: Port, oldRule: Rule, newRule: Rule, vrf: Int)
    : UpdateDownlink = {
        UpdateDownlink(
            portId = port.getId,
            vrfTable = vrf,
            oldAddress = fromV6Proto(oldRule.getNat64RuleData.getPortAddress),
            newAddress = fromV6Proto(newRule.getNat64RuleData.getPortAddress))
    }

    private def associateFip(port: Port, rule: Rule, entry: Fip64Entry, vrf: Int)
    : AssociateFip = {
        val natPool = ZoomConvert.fromProto(rule.getNat64RuleData.getNatPool,
                                            classOf[NatTarget])
        AssociateFip(portId = port.getId,
                     vrfTable = vrf,
                     floatingIp = entry.floatingIp,
                     fixedIp = entry.fixedIp,
                     localIp = fromV4Proto(port.getPortSubnet(0)),
                     natPool = natPool)
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
        scenario("Port without NAT64 created after actor started") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            Then("The actor should not receive any notification")
            actor.messages shouldBe empty

            gracefulStop(ref, 5 seconds)
        }

        scenario("Port with NAT64 create after actor started") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val downlinkMac = MAC.random()
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            val entry = addEntry(port.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            actor.messages should have size 2
            actor.messages.head shouldBe createDownlink(port, rule, 1, 1234,
                                                        downlinkMac)
            actor.messages(1) shouldBe associateFip(port, rule, entry, 1)

            gracefulStop(ref, 5 seconds)
        }

        scenario("Port does not exist") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            When("Adding a non-existing port to the downlink table")
            val portId = UUID.randomUUID()
            addEntry(portId, UUID.randomUUID())

            Then("The actor should not receive any notification")
            actor.messages shouldBe empty
        }

        scenario("Port deleted for existing downlink") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            val entry = addEntry(port.getId, router.getId)

            And("Deleting the port")
            backend.store.delete(classOf[Port], port.getId)

            Then("The actor should receive a Delete notification")
            actor.messages should have size 4
            actor.messages(2) shouldBe disassociateFip(port, entry, 1)
            actor.messages(3) shouldBe DeleteDownlink(port.getId, 1, 1)
        }

        scenario("Port deleted for non-existing downlink") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            And("Deleting the port")
            backend.store.delete(classOf[Port], port.getId)

            Then("The actor should not receive any notification")
            actor.messages shouldBe empty
        }

        scenario("Multiple downlinks use different VRF numbers") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val downlinkMac1 = MAC.random()
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac1,
                                         tunnelKey = 1234)
            val rule1 = createNat64Rule(portId = Some(port1.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1),
                                    CreateOp(rule1)))

            When("Adding a new port to the downlink table")
            val entry1 = addEntry(port1.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            actor.messages should have size 2
            actor.messages.head shouldBe createDownlink(port1, rule1, 1, 1234,
                                                        downlinkMac1)
            actor.messages(1) shouldBe associateFip(port1, rule1, entry1, 1)

            When("Adding a new port")
            val downlinkMac2 = MAC.random()
            val port2 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac2,
                                         tunnelKey = 1235)
            val rule2 = createNat64Rule(portId = Some(port2.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(port2), CreateOp(rule2)))
            val entry2 = addEntry(port2.getId, router.getId)

            Then("The actor should receive a Create and AssociateFip notification")
            actor.messages should have size 4
            actor.messages(2) shouldBe createDownlink(port2, rule2, 2, 1235,
                                                      downlinkMac2)
            actor.messages(3) shouldBe associateFip(port2, rule2, entry2, 2)

            When("The first port is deleted")
            backend.store.delete(classOf[Port], port1.getId)

            Then("The actor should receive a Delete notification")
            actor.messages should have size 6
            actor.messages(4) shouldBe disassociateFip(port1, entry1, 1)
            actor.messages(5) shouldBe DeleteDownlink(port1.getId, 1, 1234)

            When("Adding a new port")
            val downlinkMac3 = MAC.random()
            val port3 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac3,
                                         tunnelKey = 1236)
            val rule3 = createNat64Rule(portId = Some(port3.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(port3), CreateOp(rule3)))
            val entry3 = addEntry(port3.getId, router.getId)

            Then("The actor should receive a Create notification with 1 VRF")
            actor.messages should have size 8
            actor.messages(6) shouldBe createDownlink(port3, rule3, 1, 1236,
                                                      downlinkMac3)
            actor.messages(7) shouldBe associateFip(port3, rule3, entry3, 1)

            gracefulStop(ref, 5 seconds)
        }

        scenario("Downlink deleted when last FIP removed") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            val entry = addEntry(port.getId, router.getId)

            And("Removing the entry")
            removeEntry(entry)

            Then("The actor should receive a DisassociateFip and Delete notification")
            actor.messages should have size 4
            actor.messages(2) shouldBe disassociateFip(port, entry, 1)
            actor.messages(3) shouldBe DeleteDownlink(port.getId, 1, 1234)
        }

        scenario("Deleted downlink frees VRF numbers") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val downlinkMac = MAC.random()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            var entry = addEntry(port.getId, router.getId)

            And("Removing the entry")
            removeEntry(entry)

            And("Addind the same port")
            entry = addEntry(port.getId, router.getId)

            Then("The actor should receive 0 VRF")
            actor.messages should have size 6
            actor.messages(4) shouldBe createDownlink(port, rule, 1, 1234,
                                                      downlinkMac)
            actor.messages(5) shouldBe associateFip(port, rule, entry, 1)

            gracefulStop(ref, 5 seconds)
        }

        scenario("Port updates are filtered") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId))
            val rule = createNat64Rule(portId = Some(port1.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            var entry = addEntry(port1.getId, router.getId)

            And("The port is updated")
            val port2 = port1.toBuilder
                .addFipNatRuleIds(rule.getId)
                .setPortMac("01:02:03:04:05:06")
                .build()
            backend.store.update(port2)

            Then("The actor should only receive two notifications")
            actor.messages should have size 2

            gracefulStop(ref, 5 seconds)
        }
    }

    feature("VPP downlink handles rule updates") {
        scenario("NAT64 rule added to port") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val downlinkMac = MAC.random()
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Adding a new port to the downlink table")
            val entry = addEntry(port.getId, router.getId)

            Then("The actor should not receive any notification")
            actor.messages shouldBe empty

            When("Adding a NAT64 rule to the port")
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.create(rule)

            Then("The actor should not receive a Create notification")
            actor.messages should have size 2
            actor.messages.head shouldBe createDownlink(port, rule, 1, 1234,
                                                        downlinkMac)
            actor.messages(1) shouldBe associateFip(port, rule, entry, 1)

            gracefulStop(ref, 5 seconds)
        }

        scenario("NAT64 rule removed from port") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            And("The rule is deleted")
            backend.store.delete(classOf[Rule], rule.getId)

            Then("The actor should not receive additional notifications")
            actor.messages should have size 2

            gracefulStop(ref, 5 seconds)
        }

        scenario("NAT64 rule updated with port address changed") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            val rule1 = createNat64Rule(portId = Some(port.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule1)))

            When("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            And("The rule is update with a new port address")
            val rule2 = createNat64Rule(id = rule1.getId,
                                        portId = Some(port.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.update(rule2)

            Then("The actor should receive an Update notification")
            actor.messages should have size 3
            actor.messages(2) shouldBe updateDownlink(port, rule1, rule2, 1)

            gracefulStop(ref, 5 seconds)
        }

        scenario("NAT64 rule updated without port address changed") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            val rule1 = createNat64Rule(portId = Some(port.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule1)))

            When("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            And("The rule is update with a new port address")
            val rule2 = createNat64Rule(id = rule1.getId,
                                        portId = Some(port.getId),
                                        portAddress = Some(fromV6Proto(
                                            rule1.getNat64RuleData.getPortAddress)),
                                        natPool = Some(createNatTarget()))
            backend.store.update(rule2)

            Then("The actor should not receive an Update notification")
            actor.messages should have size 2

            gracefulStop(ref, 5 seconds)
        }

        scenario("Multiple NAT64 rules are ignored") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            val rule1 = createNat64Rule(portId = Some(port.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule1)))

            When("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            And("Adding a second NAT64 rule to the port")
            val rule2 = createNat64Rule(portId = Some(port.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.create(rule2)

            Then("The actor should not receive additional notifications")
            actor.messages should have size 2

            gracefulStop(ref, 5 seconds)
        }

        scenario("Any other rules are ignored") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            val rule1 = createNat64Rule(portId = Some(port.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule1)))


            When("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            And("Adding a second NAT64 rule to the port")
            val rule2 = createNatRuleBuilder(id = UUID.randomUUID(),
                                             targets = Set(createNatTarget()))
                .setFipPortId(port.getId)
                .build()
            backend.store.create(rule2)

            Then("The actor should not receive additional notifications")
            actor.messages should have size 2

            gracefulStop(ref, 5 seconds)
        }
    }

    feature("VPP downlink handles FIP updates") {
        scenario("Adding and removing FIP64 entries") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            val entry1 = addEntry(port.getId, router.getId)

            And("Adding another entry")
            val entry2 = addEntry(port.getId, router.getId)

            Then("The actor should receive AssociateFip notification")
            actor.messages should have size 3
            actor.messages(2) shouldBe associateFip(port, rule, entry2, 1)

            When("Deleting the first entry")
            removeEntry(entry1)

            Then("The actor should receive DisassociateFip notification")
            actor.messages should have size 4
            actor.messages(3) shouldBe disassociateFip(port, entry1, 1)

            When("Deleting the second entry")
            removeEntry(entry2)

            Then("The actor should receive DownlinkDeleted")
            actor.messages should have size 6
            actor.messages(4) shouldBe disassociateFip(port, entry2, 1)
            actor.messages(5) shouldBe DeleteDownlink(port.getId, 1, 1234)
        }

        scenario("All FIP entries are cleared when port deleted") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            val entry1 = addEntry(port.getId, router.getId)

            And("Adding another entry")
            val entry2 = addEntry(port.getId, router.getId)

            And("Adding another entry")
            val entry3 = addEntry(port.getId, router.getId)

            And("The port is deleted")
            backend.store.delete(classOf[Port], port.getId)

            Then("All FIPs are disassociated")
            actor.messages should have size 8
            actor.messages.slice(4, 7) should contain allOf(
                disassociateFip(port, entry1, 1),
                disassociateFip(port, entry2, 1),
                disassociateFip(port, entry3, 1))
            actor.messages(7) shouldBe DeleteDownlink(port.getId, 1, 1234)
        }
    }

    feature("VPP downlink handles table completion") {
        scenario("The table is deleted") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        tunnelKey = 1234)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Adding a new port to the downlink table")
            val entry = addEntry(port.getId, router.getId)

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
            actor.messages(2) shouldBe disassociateFip(port, entry, 1)
            actor.messages(3) shouldBe DeleteDownlink(port.getId, 1, 1234)
        }
    }

    feature("VPP downlink lifecycle") {
        scenario("Messages are not received after stop") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Stopping the downlink")
            actor.stop()

            And("Adding a new port to the downlink table")
            addEntry(port.getId, router.getId)

            Then("The VPP downlink should not receive any notification")
            actor.messages shouldBe empty
        }

        scenario("Messages are received after restart") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val downlinkMac = MAC.random()
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId),
                                        portMac = downlinkMac,
                                        tunnelKey = 1234)
            val rule = createNat64Rule(portId = Some(port.getId),
                                       portAddress = Some(randomSubnet6()),
                                       natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port),
                                    CreateOp(rule)))

            When("Stopping the downlink")
            actor.stop()

            And("Adding a new port to the downlink table")
            val entry = addEntry(port.getId, router.getId)

            And("Starting the downlink again")
            actor.start()

            Then("The actor should not receive a Create notification")
            actor.messages should have size 2
            actor.messages.head shouldBe createDownlink(port, rule, 1, 1234,
                                                        downlinkMac)
            actor.messages(1) shouldBe associateFip(port, rule, entry, 1)

            gracefulStop(ref, 5 seconds)
        }

        scenario("State is cleaned at stop") {
            Given("A VPP downlink actor")
            val (actor, ref) = createVppDownlink()

            And("A port")
            val router = createRouter()
            val port1 = createRouterPort(routerId = Some(router.getId))
            val rule1 = createNat64Rule(portId = Some(port1.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port1),
                                    CreateOp(rule1)))

            When("Adding a new port to the downlink table")
            val entry1 = addEntry(port1.getId, router.getId)

            And("Stopping the downlink")
            actor.stop()

            And("Removing the first entry")
            removeEntry(entry1)

            And("Creating a new port")
            val downlinkMac = MAC.random()
            val port2 = createRouterPort(routerId = Some(router.getId),
                                         portMac = downlinkMac,
                                         tunnelKey = 1234)
            val rule2 = createNat64Rule(portId = Some(port2.getId),
                                        portAddress = Some(randomSubnet6()),
                                        natPool = Some(createNatTarget()))
            backend.store.multi(Seq(CreateOp(port2), CreateOp(rule2)))

            And("Adding the second port to the downlink table")
            val entry2 = addEntry(port2.getId, router.getId)

            And("Starting the downlink")
            actor.start()

            Then("The actor should only receive the second port")
            actor.messages should have size 6
            actor.messages(4) shouldBe createDownlink(port2, rule2, 1, 1234,
                                                      downlinkMac)
            actor.messages(5) shouldBe associateFip(port2, rule2, entry2, 1)

            gracefulStop(ref, 5 seconds)
        }
    }
}
