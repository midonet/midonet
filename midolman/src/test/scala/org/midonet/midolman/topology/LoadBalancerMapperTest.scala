/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{LoadBalancer => TopologyLB, VIP => TopologyVIP}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{LoadBalancer => SimLB}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class LoadBalancerMapperTest extends MidolmanSpec
                             with TopologyBuilder
                             with TopologyMatchers {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        super.fillConfig(config)
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config
    }

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    private def assertThread(): Unit = {
        assert(vt.threadId == Thread.currentThread.getId)
    }

    feature("A load-balancer should come with its vips") {
        scenario("The mapper emits error for non-existing load-balancers") {
            Given("An load-balancer identifier")
            val id = UUID.randomUUID

            And("An load-balancer mapper")
            val mapper = new LoadBalancerMapper(id, vt)

            And("An observer to the load-balancer mapper")
            val obs = new AwaitableObserver[SimLB](1, assertThread())

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.await(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyLB]
            e.id shouldBe id
        }

        scenario("Adding-removing vips from the load-balancer") {
            Given("A load-balancer with no vips")
            val protoLB = buildAndStoreLB()

            Then("When we subscribe to the load-balancer observable")
            val lbMapper = new LoadBalancerMapper(protoLB.getId.asJava, vt)
            val observable = Observable.create(lbMapper)
            val lbObs = new AwaitableObserver[SimLB](1, assertThread())
            observable.subscribe(lbObs)

            Then("We obtain a simulation load-balancer with no vips")
            lbObs.await(timeout, 1) shouldBe true
            lbObs.getOnNextEvents should have size 1
            var simLB = lbObs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf protoLB

            And("when we add a vip to the load-balancer")
            val vip1 = buildAndStoreVip("192.168.0.1")
            var updatedProtoLB = addVipToLoadBalancer(protoLB, vip1.getId)

            Then("We receive the load-balancer with one vip")
            lbObs.await(timeout, 1)
            lbObs.getOnNextEvents should have size 2
            simLB = lbObs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            simLB.vips.toList.map(_ shouldBeDeviceOf vip1)

            And("When we update the vip")
            val updatedVip1 = vip1.toBuilder
                .setLoadBalancerId(protoLB.getId)
                .setStickySourceIp(true)
                .build()
            store.update(updatedVip1)

            Then("We receive the load-balancer with the updated VIP")
            lbObs.await(timeout, 1)
            lbObs.getOnNextEvents should have size 3
            simLB = lbObs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            simLB.vips should contain theSameElementsAs Set(updatedVip1)

            And("When we add a 2nd vip to the load-balancer")
            val vip2 = buildAndStoreVip("192.168.0.2")
            updatedProtoLB = addVipToLoadBalancer(updatedProtoLB, vip2.getId)

            Then("We receive the load-balancer with 2 vips")
            lbObs.await(timeout, 1)
            lbObs.getOnNextEvents should have size 4
            simLB = lbObs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            simLB.vips should contain theSameElementsAs Set(updatedVip1, vip2)
        }
    }

    feature("The load-balancer mapper handles deletion correctly") {
        scenario("Deleting a load-balancer") {

        }
    }

    private def addVipToLoadBalancer(loadBalancer: TopologyLB,
                                     vipId: Commons.UUID)
    : TopologyLB = {

        val updatedLB = loadBalancer.toBuilder
            .addVipIds(vipId)
            .build()
        store.update(updatedLB)

        updatedLB
    }

    private def buildAndStoreVip(ip: String): TopologyVIP = {
        val vip = createVip(adminStateUp = Some(true),
                            poolId = Some(UUID.randomUUID()),
                            address = Some(IPv4Addr(ip)),
                            protocolPort = Some(7777),
                            isStickySourceIp = Some(false))
        store.create(vip)
        vip
    }

    private def buildAndStoreLB(): TopologyLB = {
        val loadBalancer = createLB(adminStateUp = Some(true),
                                    routerId = Some(UUID.randomUUID()),
                                    vips = Set.empty)
        store.create(loadBalancer)
        loadBalancer
    }
}
