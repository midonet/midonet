/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.UUID
import scala.compat.Platform

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.simulation.{Pool, PacketContext, LoadBalancer}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{PoolRequest, LoadBalancerRequest}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Addr, TCP}
import org.midonet.sdn.flows.{FlowTagger, WildcardMatch}

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
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
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
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
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
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
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
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
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
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
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
            val ingressMatch = new WildcardMatch()
                .setNetworkDestination(IPv4Addr.fromString(firstVip.getAddress))
                .setTransportDestination(firstVip.getProtocolPort)
                .setNetworkSource(IPv4Addr.fromString("1.1.1.1"))
                .setTransportSource(1)
                .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            val pktContextIngress = new PacketContext(Left(1), null,
                Platform.currentTime + 10000, null, null, None,
                ingressMatch)(actorSystem)

            try {
                lb.processInbound(pktContextIngress)(executionContext, actorSystem)
            } catch { case _: NotYetException => }

            Then("the VTA should receive a pool request")
            val vtaMessages = vta.getAndClear()
            vtaMessages.size shouldBe 3

            vtaMessages.contains(poolReqMsg(pool.getId)) shouldBe true

            And("the VTA should receive the pool itself")
            val poolMessages = vtaMessages.filter(m => m.isInstanceOf[Pool])
            poolMessages.size shouldBe 1

            And("the VTA should receive a flow invalidation for the pool")
            vtaMessages.contains(flowInvalidationMsg(pool.getId)) shouldBe true
        }
    }

    def poolReqMsg(id: UUID) =
        PoolRequest(id)

    def flowInvalidationMsg(id: UUID) =
        InvalidateFlowsByTag(FlowTagger.tagForDevice(id))
}
