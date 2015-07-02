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

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.{Router, LoadBalancer}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{LoadBalancerRequest,
                                                           RouterRequest}
import org.midonet.midolman.util.MidolmanSpec


@RunWith(classOf[JUnitRunner])
class RouterManagerTest extends TestKit(ActorSystem("RouterManagerTest"))
        with MidolmanSpec
        with ImplicitSender {
    var vta: TestableVTA = null

    registerActors(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    feature("RouterManager successfully delivers Router to VTA") {
        scenario("Create router with loadbalancer") {
            Given("a router with one loadbalancer")
            val router = newRouter("router1")
            val loadBalancer = newLoadBalancer()
            setLoadBalancerOnRouter(loadBalancer, router)

            When("the VTA receives a request for the router")
            vta.self ! RouterRequest(router, true)

            Then("it should return the requested router, with correct loadbalancer ID")
            val r = expectMsgType[Router]
            r.cfg.loadBalancer shouldEqual loadBalancer.getId

            And("The associated load balancer should be updated")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = false)
            val lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual r.id

            Then("Delete load balancer")
            deleteLoadBalancer(loadBalancer.getId)

            Then("it should return the requested router, with null loadbalancer ID")
            val r2 = expectMsgType[Router]
            r2.cfg.loadBalancer shouldBe null
        }
        scenario("The load balancer gets updated when the routerId changes") {
            Given ("a load balancer")
            val loadBalancer = newLoadBalancer()
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            var lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual null

            val router = newRouter("r")
            setLoadBalancerOnRouter(loadBalancer, router)

            lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual router

            setLoadBalancerOnRouter(null, router)

            lb = expectMsgType[LoadBalancer]
            lb.routerId shouldEqual null
        }
    }
}
