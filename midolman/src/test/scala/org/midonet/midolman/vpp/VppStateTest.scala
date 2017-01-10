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
import scala.collection.mutable
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppState.NatPool
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class VppStateTest extends MidolmanSpec {

    private var vt: VirtualTopology = _

    class TestableVppState extends VppState {

        private val uplinks = new mutable.HashMap[UUID, Seq[UUID]]()
        override def vt = VppStateTest.this.vt

        def splitPool(natPool: IPv4Subnet, portId: UUID, portIds: Seq[UUID])
        : NatPool = {
            super.splitPool(natPool, portId, portIds.asJava)
        }

        def add(portId: UUID, portIds: Seq[UUID]): Unit = {
            uplinks += portId -> portIds
        }

        def remove(portId: UUID): Unit = {
            uplinks -= portId
        }

        def get(portId: UUID, natPool: IPv4Subnet): Future[Option[NatPool]] = {
            poolFor(portId, natPool)
        }

        protected override def uplinkPortFor(downlinkPortId: UUID): UUID = {
            if (uplinks.size == 1) uplinks.head._1
            else null
        }

        protected override def uplinkPortsFor(uplinkPortId: UUID): util.List[UUID] = {
            uplinks(uplinkPortId).asJava
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
            TestableVppState.splitPool(pool, id1, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 42))

            And("Second gateway should get the second partition")
            TestableVppState.splitPool(pool, id2, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 43), IPv4Addr.fromInt(1 << 30 | 84))

            And("Third gateway should get the third partition")
            TestableVppState.splitPool(pool, id3, ids) shouldBe NatPool(
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
            TestableVppState.splitPool(pool, id1, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 31))

            And("Second gateway should get the second partition")
            TestableVppState.splitPool(pool, id2, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 32), IPv4Addr.fromInt(1 << 30 | 63))

            And("Third gateway should get the third partition")
            TestableVppState.splitPool(pool, id3, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 64), IPv4Addr.fromInt(1 << 30 | 94))

            And("Fourth gateway should get the fourth partition")
            TestableVppState.splitPool(pool, id4, ids) shouldBe NatPool(
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
            TestableVppState.splitPool(pool, id1, ids2) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 42))

            And("Second gateway should get the second partition")
            TestableVppState.splitPool(pool, id2, ids3) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 43), IPv4Addr.fromInt(1 << 30 | 84))

            And("Third gateway should get the third partition")
            TestableVppState.splitPool(pool, id3, ids1) shouldBe NatPool(
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
            TestableVppState.splitPool(pool, id1, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 0))

            And("Second gateway should get the first address")
            TestableVppState.splitPool(pool, id2, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 1), IPv4Addr.fromInt(1 << 30 | 1))

            And("Third gateway should get no address")
            TestableVppState.splitPool(pool, id3, ids) shouldBe NatPool(
                IPv4Addr.fromInt(1 << 30 | 2), IPv4Addr.fromInt(1 << 30 | 1))

            And("Fourth gateway should get the second address")
            TestableVppState.splitPool(pool, id4, ids) shouldBe NatPool(
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
            val allocated = vpp.get(portId, pool).await()

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
            val allocated = vpp.get(portId, pool).await()

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
            val allocated = vpp.get(portId, pool).await()

            Then("The allocation should return a slice of the NAT pool")
            allocated shouldBe Some(NatPool(IPv4Addr.fromInt(1 << 30 | 64),
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
            val allocated = vpp.get(portId, pool).await()

            Then("The allocation should return no NAT pool")
            allocated shouldBe None
        }
    }

}
