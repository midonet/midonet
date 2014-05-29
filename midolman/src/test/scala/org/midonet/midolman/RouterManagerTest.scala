/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.{Router, CustomMatchers, LoadBalancer}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{LoadBalancerRequest,
                                                           RouterRequest}
import org.midonet.midolman.util.MidolmanSpec


@RunWith(classOf[JUnitRunner])
class RouterManagerTest extends TestKit(ActorSystem("RouterManagerTest"))
        with MidolmanSpec
        with ImplicitSender {
    var vta: TestableVTA = null

    protected override def registerActors =
        List(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    feature("RouterManager successfully delivers Router to VTA") {
        scenario("Create router with loadbalancer") {
            Given("a router with one loadbalancer")
            val router = newRouter("router1")
            val loadBalancer = createLoadBalancer()
            setLoadBalancerOnRouter(loadBalancer, router)

            When("the VTA receives a request for the router")
            vta.self ! RouterRequest(router.getId, true)

            Then("it should return the requested router, with correct loadbalancer ID")
            val r = expectMsgType[Router]
            r.loadBalancer.id shouldEqual loadBalancer.getId

            And("The associated load balancer should be updated")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = false)
            val lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual r.id

            Then("Delete load balancer")
            deleteLoadBalancer(loadBalancer.getId)

            Then("it should return the requested router, with null loadbalancer ID")
            val r2 = expectMsgType[Router]
            assert(r2.loadBalancer == null)
        }
        scenario("The load balancer gets updated when the routerId changes") {
            Given ("a load balancer")
            val loadBalancer = createLoadBalancer()
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            var lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual null

            val router = newRouter("r")
            setLoadBalancerOnRouter(loadBalancer, router)

            lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual router.getId

            setLoadBalancerOnRouter(null, router)

            lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual null
        }
    }
}
