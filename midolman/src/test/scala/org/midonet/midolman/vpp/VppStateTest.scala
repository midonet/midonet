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
import java.util

import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder.DefaultValue
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.topology.{GatewayMappingService, VirtualTopology}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppState.GatewaysChanged
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService

@RunWith(classOf[JUnitRunner])
class VppStateTest extends MidolmanSpec with Matchers with GivenWhenThen {

    private var vt: VirtualTopology = _

    class TestableVppState extends VppExecutor with VppState {

        protected override def vt: VirtualTopology = VppStateTest.this.vt
        var messages = List[Any]()

        protected override def newExecutor: ExecutorService = {
            new SameThreadButAfterExecutorService
        }

        def splitPool(natPool: IPv4Subnet, portId: UUID, portIds: Seq[UUID])
        : NatPool = {
            super.splitPool(natPool, portId, portIds.asJava)
        }

        def add(portId: UUID, portIds: Seq[UUID]): Unit = {
            addUplink(portId, portIds.asJava)
        }

        def remove(portId: UUID): Unit = {
            removeUplink(portId)
        }

        def get(portId: UUID, natPool: IPv4Subnet): Option[NatPool] = {
            poolFor(portId, natPool)
        }

        override def doStart(): Unit = {
            notifyStarted()
        }

        protected def receive: VppExecutor.Receive = {
            case msg:Any =>
                log debug s"Recieved message: $msg"
                messages = messages :+ msg
                Future.successful(None)
        }
    }
    object TestableVppState extends TestableVppState

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("NAT pool is split between multiple gateways") {
        scenario("Gateways receive disjoint equal partitions") {
            Given("A NAT pool with 128 addresses")
            val pool = new IPv4Subnet(1 << 30, 25)

            And("Three gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val ids = Seq(id2, id1, id3)

            Then("First gateway should get the first partition")
            TestableVppState.splitPool(pool, id1, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 42))

            And("Second gateway should get the second partition")
            TestableVppState.splitPool(pool, id2, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 43), IPv4Addr.fromInt(1 << 30 | 84))

            And("Third gateway should get the third partition")
            TestableVppState.splitPool(pool, id3, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 85), IPv4Addr.fromInt(1 << 30 | 126))
        }

        scenario("Gateways receive disjoint inequal partitions") {
            Given("A NAT pool with 128 addresses")
            val pool = new IPv4Subnet(1 << 30, 25)

            And("Four gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val id4 = new UUID(0L, 3L)
            val ids = Seq(id2, id4, id3, id1)

            Then("First gateway should get the first partition")
            TestableVppState.splitPool(pool, id1, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 31))

            And("Second gateway should get the second partition")
            TestableVppState.splitPool(pool, id2, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 32), IPv4Addr.fromInt(1 << 30 | 63))

            And("Third gateway should get the third partition")
            TestableVppState.splitPool(pool, id3, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 64), IPv4Addr.fromInt(1 << 30 | 94))

            And("Fourth gateway should get the fourth partition")
            TestableVppState.splitPool(pool, id4, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 95), IPv4Addr.fromInt(1 << 30 | 126))

        }

        scenario("Gateways receive same partitions even when order is different") {
            Given("A NAT pool with 128 addresses")
            val pool = new IPv4Subnet(1 << 30, 25)

            And("Three gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val ids1 = Seq(id2, id3, id1)
            val ids2 = Seq(id1, id2, id3)
            val ids3 = Seq(id1, id3, id2)

            Then("First gateway should get the first partition")
            TestableVppState.splitPool(pool, id1, ids2) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 42))

            And("Second gateway should get the second partition")
            TestableVppState.splitPool(pool, id2, ids3) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 43), IPv4Addr.fromInt(1 << 30 | 84))

            And("Third gateway should get the third partition")
            TestableVppState.splitPool(pool, id3, ids1) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 85), IPv4Addr.fromInt(1 << 30 | 126))
        }

        scenario("Some gateways may not receive addresses for small pool") {
            Given("A NAT pool with 2 addresses")
            val pool = new IPv4Subnet(1 << 30, 30)

            And("Four gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val id4 = new UUID(0L, 3L)
            val ids = Seq(id2, id3, id1, id4)

            Then("First gateway should get no address")
            TestableVppState.splitPool(pool, id1, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 0))

            And("Second gateway should get the first address")
            TestableVppState.splitPool(pool, id2, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 1))

            And("Third gateway should get no address")
            TestableVppState.splitPool(pool, id3, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 2), IPv4Addr.fromInt(1 << 30 | 1))

            And("Fourth gateway should get the second address")
            TestableVppState.splitPool(pool, id4, ids) shouldBe new NatPool(
                IPv4Addr.fromInt(1 << 30 | 2), IPv4Addr.fromInt(1 << 30 | 2))
        }
    }

    feature("VPP state maintains uplink ports and returns allocated NAT pool") {
        scenario("No uplink port") {
            Given("A VPP state")
            val vpp = new TestableVppState

            And("A port with a NAT pool")
            val portId = UUID.randomUUID()
            val pool = new IPv4Subnet(1 << 30, 10)

            When("Requesting the allocated NAT pool")
            val allocated = vpp.get(portId, pool)

            Then("The allocation should return no NAT pool")
            allocated shouldBe None
        }

        scenario("A single uplink port in a port group") {
            Given("A VPP state")
            val vpp = new TestableVppState

            And("An uplink port")
            val uplinkPortId = new UUID(0L, 0L)
            vpp.add(uplinkPortId, Seq(uplinkPortId))

            And("A port with a NAT pool")
            val portId = UUID.randomUUID()
            val pool = new IPv4Subnet(1 << 30, 24)

            When("Requesting the allocated NAT pool")
            val allocated = vpp.get(portId, pool)

            Then("The allocation should return the NAT pool")
            allocated shouldBe Some(NatPool(IPv4Addr(1 << 30 | 1),
                                            IPv4Addr(1 << 30 | 254)))
        }

        scenario("A single uplink port in a port group of four") {
            Given("A VPP state")
            val vpp = new TestableVppState

            And("An uplink port")
            val uplinkPortId = new UUID(0L, 2L)
            vpp.add(uplinkPortId, Seq(uplinkPortId,
                                      new UUID(0L, 0L),
                                      new UUID(0L, 1L),
                                      new UUID(0L, 3L)))

            And("A port with a NAT pool")
            val portId = UUID.randomUUID()
            val pool = new IPv4Subnet(1 << 30, 25)

            When("Requesting the allocated NAT pool")
            val allocated = vpp.get(portId, pool)

            Then("The allocation should return a slice of the NAT pool")
            allocated shouldBe Some(new NatPool(
                                        IPv4Addr.fromInt(1 << 30 | 64),
                                        IPv4Addr.fromInt(1 << 30 | 94)))
        }

        scenario("Multiple uplink ports") {
            Given("A VPP state")
            val vpp = new TestableVppState

            And("An uplink port")
            vpp.add(new UUID(0L, 0L), Seq(new UUID(0L, 0L)))
            vpp.add(new UUID(0L, 1L), Seq(new UUID(0L, 1L)))

            And("A port with a NAT pool")
            val portId = UUID.randomUUID()
            val pool = new IPv4Subnet(1 << 30, 24)

            When("Requesting the allocated NAT pool")
            val allocated = vpp.get(portId, pool)

            Then("The allocation should return no NAT pool")
            allocated shouldBe None
        }

        scenario("On new gateway VPP reconfigure control vxlan OVS flows") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)

            And("A gateway table")
            val table = vt.stateTables
                .getTable[UUID, AnyRef](MidonetBackend.GatewayTable)
            table.start()

            And("The service is started")
            service.startAsync().awaitRunning()

            Then("The gateway table should be empty")
            service.gateways.hasMoreElements shouldBe false

            When("Adding a gateway")
            val id = UUID.randomUUID()
            table.add(id, DefaultValue)

            Then("The service should return the gateway")
            service.gateways.nextElement() shouldBe id

            Given("A VPP state")
            val vpp = new TestableVppState
            vpp.startAsync().awaitRunning()
            And("An uplink port")
            vpp.add(new UUID(0L, 0L), Seq(new UUID(0L, 0L)))

            vpp.messages should have size 1
            val hosts = Set[UUID](id)
            vpp.messages(0) shouldBe GatewaysChanged(hosts)
        }
    }

}
