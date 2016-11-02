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

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.junit.runner.RunWith
import org.midonet.odp.FlowMatch
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.{Pool, PacketContext, LoadBalancer}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{Ask, InvalidateFlowsByTag, PoolRequest, LoadBalancerRequest}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Addr, TCP}
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class LoadBalancerManagerTest extends TestKit(ActorSystem("LoadBalancerManagerTest"))
        with MidolmanSpec
        with ImplicitSender {
    var vta: TestableVTA = null

    registerActors(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    feature("LoadBalancerManager handles loadBalancer's VIPs") {
        scenario("Load loadBalancer with two VIPs") {
            Given("a loadBalancer with two VIPs")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val vips = (0 until 2).map(n => createVip(pool))
            val vipIds = vips.map(v => v.getId).toSet
            vips.size shouldBe 2

            When("the VTA receives a request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId)

            Then("it should return the requested loadBalancer, including the VIPs")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 2
            lb.vips.map(v => v.id).toSet shouldEqual vipIds

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(lbFlowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when a VIP is added") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstVip = createVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("a new VIP is added")
            createVip(pool)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 2

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(lbFlowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when a VIP is removed") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstVip = createVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("the existing VIP is removed")
            deleteVip(firstVip)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 0

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(lbFlowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when a VIP is changed") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstVip = createVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("the VIP is changed")
            setVipAdminStateUp(firstVip, false)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 1

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(lbFlowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when loadbalancer is changed") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstVip = createVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.adminStateUp shouldBe true
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("the loadBalancer gets set to admin state down")
            setLoadBalancerDown(loadBalancer)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 1
            lb2.adminStateUp shouldBe false

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(lbFlowInvalidationMsg(lb.id)) shouldBe true
        }

    }

    feature("Loadbalancer logic requests Pool when needed") {
        scenario("Pool requested when VIP traffic flows through loadBalancer") {
            Given("a loadBalancer with a pool")
            val loadBalancer = createLoadBalancer()
            val pool = createPool(loadBalancer)
            val firstVip = createVip(pool)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.adminStateUp shouldBe true
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("traffic is sent through the loadbalancer")
            // Ingress match for packet destined to VIP
            val ingressMatch = new FlowMatch()
                .setNetworkDst(IPv4Addr.fromString(firstVip.getAddress))
                .setDstPort(firstVip.getProtocolPort)
                .setNetworkSrc(IPv4Addr.fromString("1.1.1.1"))
                .setSrcPort(1)
                .setNetworkProto(TCP.PROTOCOL_NUMBER)
            val pktContextIngress = new PacketContext(1, null, ingressMatch)

            intercept[NotYetException] {
                lb.processInbound(pktContextIngress)(actorSystem)
            }

            Then("the VTA should receive a pool request")
            val vtaMessages = vta.getAndClear()
            vtaMessages.size shouldBe 3

            vtaMessages(0).asInstanceOf[Ask].request shouldBe poolReqMsg(pool.getId)

            And("the VTA should receive the pool itself")
            val poolMessages = vtaMessages.filter(m => m.isInstanceOf[Pool])
            poolMessages.size shouldBe 1

            And("the VTA should receive a flow invalidation for the pool")
            vtaMessages.contains(poolFlowInvalidationMsg(pool.getId)) shouldBe true
        }
    }

    feature("Loadbalancer pool delete is idempotent") {
        scenario("Deletion of a pool should succeed for non-existing" +
                 "Midonet pool") {
            val loadBalancer = createLoadBalancer()
            val poolId = new UUID(0x1234L, 0x4321L)
            try {
                deletePool(poolId)
            } catch {
                case _: Throwable => assert(false)
            }
        }
    }

    def poolReqMsg(id: UUID) =
        PoolRequest(id)

    def lbFlowInvalidationMsg(id: UUID) =
        InvalidateFlowsByTag(FlowTagger.tagForLoadBalancer(id))

    def poolFlowInvalidationMsg(id: UUID) =
        InvalidateFlowsByTag(FlowTagger.tagForPool(id))

}
