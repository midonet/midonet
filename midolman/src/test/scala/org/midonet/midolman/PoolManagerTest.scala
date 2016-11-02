/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.{CustomMatchers, Pool}
import org.midonet.midolman.state.l4lb.PoolLBMethod
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{InvalidateFlowsByTag, PoolRequest}
import java.util.UUID

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem

import org.midonet.cluster.data.l4lb.PoolMember
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class PoolManagerTest extends TestKit(ActorSystem("PoolManagerTest"))
        with MidolmanSpec
        with ImplicitSender{

    var vta: TestableVTA = null

    registerActors(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    feature("PoolManager handles pool's PoolMembers") {
        scenario("Load pool with two PoolMembers") {
            Given("a pool with two PoolMembers")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val poolMembers = (0 until 2).map(_ => createPoolMember(pool))
            val poolMemberIds = poolMembers.map(v => v.getId).toSet
            poolMembers.size shouldBe 2

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the requested pool, including the PoolMembers")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.activePoolMembers.size shouldBe 2
            p2.activePoolMembers.map(v => v.id).toSet shouldEqual poolMemberIds

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }

        scenario("Receive update when a PoolMember is added") {
            Given("a pool with one PoolMember")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstPoolMember = createPoolMember(pool)

            When("the VTA receives a subscription request for the pool")
            vta.self ! PoolRequest(pool.getId, update = true)

            And("it returns the first version of the pool")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.activePoolMembers.size shouldBe 1
            vta.getAndClear()

            And("a new PoolMember is added")
            val secondPoolMember = createPoolMember(pool)

            Then("the VTA should send an update")
            val p3 = expectMsgType[Pool]
            p3.id shouldEqual pool.getId
            p3.activePoolMembers.size shouldBe 2
            p3.activePoolMembers.map(v => v.id).toSet shouldEqual Set(firstPoolMember.getId, secondPoolMember.getId)

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }

        scenario("Receive update when a PoolMember is removed") {
            Given("a pool with one PoolMember")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstPoolMember = createPoolMember(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolRequest(pool.getId, update = true)

            And("it returns the first version of the pool")
            val p1 = expectMsgType[Pool]
            p1.id shouldEqual pool.getId
            p1.activePoolMembers.size shouldBe 1
            vta.getAndClear()

            And("the existing PoolMember is removed")
            deletePoolMember(firstPoolMember)

            Then("the VTA should send an update")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.activePoolMembers.size shouldBe 0

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }

        scenario("Receive update when a PoolMember is changed") {
            Given("a pool with one PoolMember")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstPoolMember = createPoolMember(pool)
            firstPoolMember.getAdminStateUp shouldBe true

            When("the VTA receives a subscription request for it")
            vta.self ! PoolRequest(pool.getId, update = true)

            And("it returns the first version of the pool")
            val p1 = expectMsgType[Pool]
            p1.id shouldEqual pool.getId
            p1.activePoolMembers.size shouldBe 1
            val pm1 = p1.activePoolMembers.toSeq(0)
            vta.getAndClear()

            And("the PoolMember is changed, set to adminState down")
            setPoolMemberAdminStateUp(firstPoolMember, false)

            Then("the VTA should send an update")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.activePoolMembers.size shouldBe 0

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }
        scenario("PooMember delete is idempotent") {
            val loadBalancer = createLoadBalancer()
            val poolId = new UUID(0x1234L, 0x4321L)
            createPool(loadBalancer, poolId)
            val nonExistingId = new UUID(0x1, 0x2)
            val poolMember = new PoolMember()
            poolMember.setId(nonExistingId)
            try {
                deletePoolMember(poolMember)
            } catch {
                case _: Throwable => assert(false)
            }
        }
    }

    feature("PoolManager handles pool's adminStateUp property") {
        scenario("Create a pool with adminStateUp = true") {
            Given("A pool created with adminStateUp = true")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the pool with adminStateUp = true")
            val vtaPool = expectMsgType[Pool]
            vtaPool.adminStateUp shouldBe true
        }

        scenario("Create a pool with adminStateUp = false") {
            Given("a pool created with adminStateUp = false")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer, adminStateUp = false)

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the pool with adminStateUp = true")
            val vtaPool = expectMsgType[Pool]
            vtaPool.adminStateUp shouldBe false
        }

        scenario("Update pool's adminStateUp property") {
            Given("a pool created with adminStateUp = false")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer, adminStateUp = false)
            vta.self ! PoolRequest(pool.getId, update = true)
            val simPool1 = expectMsgType[Pool]
            simPool1.adminStateUp shouldBe false
            vta.getAndClear()

            When("the pool's adminStateUp property is set to true")
            setPoolAdminStateUp(pool, true)

            Then("the VTA should receive a flow invalidation message")
            vta.getAndClear().contains(flowInvalidationMsg(pool.getId))

            And("send an updated Pool")
            val simPool2 = expectMsgType[Pool]
            simPool2.adminStateUp shouldBe true

            When("the pool's adminStateUp property is set back to false")
            setPoolAdminStateUp(pool, false)

            Then("the VTA should receive a flow invalidation message")
            vta.getAndClear().contains(flowInvalidationMsg(pool.getId))

            And("send an updated Pool")
            val simPool3 = expectMsgType[Pool]
            simPool3.adminStateUp shouldBe false
        }
    }

    feature("PoolManager handles pool's lbMethod property") {
        scenario("Create a pool with lbMethod = 'ROUND_ROBIN'") {
            Given("A pool created with lbMethod = 'ROUND_ROBIN'")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer, lbMethod = PoolLBMethod.ROUND_ROBIN)

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the pool with lbMethod = 'ROUND_ROBIN'")
            val vtaPool = expectMsgType[Pool]
            vtaPool.lbMethod shouldBe PoolLBMethod.ROUND_ROBIN
        }

        scenario("Update pool's lbMethod property") {
            Given("a pool created with lbMethod = 'ROUND_ROBIN'")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer, lbMethod = PoolLBMethod.ROUND_ROBIN)
            vta.self ! PoolRequest(pool.getId, update = true)
            val simPool1 = expectMsgType[Pool]
            simPool1.lbMethod shouldBe PoolLBMethod.ROUND_ROBIN
            vta.getAndClear()

            When("the pool's lbMethod property is set to a different value")
            setPoolLbMethod(pool, null)

            Then("the VTA should receive a flow invalidation message")
            vta.getAndClear().contains(flowInvalidationMsg(pool.getId))

            And("send an updated Pool")
            val simPool2 = expectMsgType[Pool]
            simPool2.lbMethod shouldBe null
        }
    }

    def flowInvalidationMsg(id: UUID) =
        InvalidateFlowsByTag(FlowTagger.tagForPool(id))
}
