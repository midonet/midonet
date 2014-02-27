/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.{Router, CustomMatchers}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.RouterRequest


@RunWith(classOf[JUnitRunner])
class RouterManagerTest extends TestKit(ActorSystem("RouterManagerTest"))
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

    feature("RouterManager successfully delivers Router to VTA") {
        scenario("Create router with loadbalancer") {
            Given("a router with one loadbalancer")
            val router = newRouter("router1")
            val loadBalancer = createLoadBalancer()
            setLoadBalancerOnRouter(loadBalancer, router)

            When("the VTA receives a request for the router")
            vta.self ! RouterRequest(router.getId)

            Then("it should return the requested router, with correct loadbalancer ID")
            val r = expectMsgType[Router]
            r.loadBalancer.id shouldEqual loadBalancer.getId
        }
    }
}
