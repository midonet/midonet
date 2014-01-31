/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.simulation.{Pool, CustomMatchers}
import org.midonet.midolman.topology.{FlowTagger, VirtualTopologyActor}
import org.midonet.midolman.topology.VirtualTopologyActor.PoolRequest
import java.util.UUID
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem

@RunWith(classOf[JUnitRunner])
class PoolManagerTest extends TestKit(ActorSystem("PoolManagerTest"))
with FeatureSpecLike
with CustomMatchers
with GivenWhenThen
with ImplicitSender
with Matchers
with MidolmanServices
with MockMidolmanActors
with OneInstancePerTest
with VirtualConfigurationBuilders {

    var vta: TestableVTA = null

    protected override def registerActors =
        List(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    feature("PoolManager handles pool's PoolMembers") {
        scenario("Load pool with two PoolMembers") {
            Given("a pool with two PoolMembers")
            val pool = createPool()
            val poolMembers = (0 until 2).map(_ => createPoolMember(pool))
            val poolMemberIds = poolMembers.map(v => v.getId).toSet
            poolMembers.size shouldBe 2

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the requested pool, including the PoolMembers")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.poolMembers.size shouldBe 2
            p2.poolMembers.map(v => v.id).toSet shouldEqual poolMemberIds

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }

        scenario("Receive update when a PoolMember is added") {
            Given("a pool with one PoolMember")
            val pool = createPool()
            val firstPoolMember = createPoolMember(pool)

            When("the VTA receives a subscription request for the pool")
            vta.self ! PoolRequest(pool.getId, update = true)

            And("it returns the first version of the pool")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.poolMembers.size shouldBe 1
            vta.getAndClear()

            And("a new PoolMember is added")
            val secondPoolMember = createPoolMember(pool)

            Then("the VTA should send an update")
            val p3 = expectMsgType[Pool]
            p3.id shouldEqual pool.getId
            p3.poolMembers.size shouldBe 2
            p3.poolMembers.map(v => v.id).toSet shouldEqual Set(firstPoolMember.getId, secondPoolMember.getId)

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }

        scenario("Receive update when a PoolMember is removed") {
            Given("a pool with one PoolMember")
            val pool = createPool()
            val firstPoolMember = createPoolMember(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! PoolRequest(pool.getId, update = true)

            And("it returns the first version of the pool")
            val p1 = expectMsgType[Pool]
            p1.id shouldEqual pool.getId
            p1.poolMembers.size shouldBe 1
            vta.getAndClear()

            And("the existing PoolMember is removed")
            removePoolMemberFromPool(firstPoolMember, pool)

            Then("the VTA should send an update")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.poolMembers.size shouldBe 0

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }

        scenario("Receive update when a PoolMember is changed") {
            Given("a pool with one PoolMember")
            val pool = createPool()
            val firstPoolMember = createPoolMember(pool)
            firstPoolMember.getAdminStateUp shouldBe true

            When("the VTA receives a subscription request for it")
            vta.self ! PoolRequest(pool.getId, update = true)

            And("it returns the first version of the pool")
            val p1 = expectMsgType[Pool]
            p1.id shouldEqual pool.getId
            p1.poolMembers.size shouldBe 1
            val pm1 = p1.poolMembers.toSeq(0)
            pm1.adminStateUp shouldBe true
            //vta.getAndClear()

            And("the PoolMember is changed, set to adminState down")
            setPoolMemberAdminStateUp(firstPoolMember, false)

            Then("the VTA should send an update")
            val p2 = expectMsgType[Pool]
            p2.id shouldEqual pool.getId
            p2.poolMembers.size shouldBe 1
            val pm2 = p2.poolMembers.toSeq(0)
            pm2.adminStateUp shouldBe false

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(p2.id)) shouldBe true
        }
    }

    feature("PoolManager handles pool's adminStateUp property") {
        scenario("Create a pool with adminStateUp = true") {
            Given("A pool created with adminStateUp = true")
            val pool = createPool(adminStateUp = true)

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the pool with adminStateUp = true")
            val vtaPool = expectMsgType[Pool]
            vtaPool.adminStateUp shouldBe true
        }

        scenario("Create a pool with adminStateUp = false") {
            Given("a pool created with adminStateUp = false")
            val pool = createPool(adminStateUp = false)

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the pool with adminStateUp = true")
            val vtaPool = expectMsgType[Pool]
            vtaPool.adminStateUp shouldBe false
        }

        scenario("Update pool's adminStateUp property") {
            Given("a pool created with adminStateUp = false")
            val pool = createPool(adminStateUp = false)
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
            val pool = createPool(lbMethod = "ROUND_ROBIN")

            When("the VTA receives a request for it")
            vta.self ! PoolRequest(pool.getId)

            Then("it should return the pool with lbMethod = 'ROUND_ROBIN'")
            val vtaPool = expectMsgType[Pool]
            vtaPool.lbMethod shouldBe "ROUND_ROBIN"
        }

        scenario("Update pool's lbMethod property") {
            Given("a pool created with lbMethod = 'ROUND_ROBIN'")
            val pool = createPool(lbMethod = "ROUND_ROBIN")
            vta.self ! PoolRequest(pool.getId, update = true)
            val simPool1 = expectMsgType[Pool]
            simPool1.lbMethod shouldBe "ROUND_ROBIN"
            vta.getAndClear()

            When("the pool's lbMethod property is set to a different value")
            setPoolLbMethod(pool, "ANGULAR_ALBATROSS")

            Then("the VTA should receive a flow invalidation message")
            vta.getAndClear().contains(flowInvalidationMsg(pool.getId))

            And("send an updated Pool")
            val simPool2 = expectMsgType[Pool]
            simPool2.lbMethod shouldBe "ANGULAR_ALBATROSS"
        }
    }

    def flowInvalidationMsg(id: UUID) =
        InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(id))
}
