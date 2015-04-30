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
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{LoadBalancer => TopologyLB, VIP => TopologyVIP}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{LoadBalancer => SimLB, VIP => SimVIP}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.reactivex.{AssertableObserver, AwaitableObserver}
import rx.observers.TestObserver

@RunWith(classOf[JUnitRunner])
class LoadBalancerMapperTest extends MidolmanSpec
                             with TopologyBuilder
                             with TopologyMatchers {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    private def assertThread(): Unit = {
        assert(vt.vtThreadId == Thread.currentThread.getId)
    }

    private def makeObservable() = new TestObserver[SimLB]
                                   with AwaitableObserver[SimLB]
                                   with AssertableObserver[SimLB] {
        override def assert() = assertThread()
    }

    feature("The load-balancer mapper emits proper simulation objects") {
        scenario("The mapper emits error for non-existing load-balancers") {
            Given("A load-balancer identifier")
            val id = UUID.randomUUID

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(id, vt)

            And("An observer to the load-balancer mapper")
            val obs = makeObservable()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyLB]
            e.id shouldBe id
        }

        scenario("Adding-removing vips from the load-balancer") {
            Given("A load-balancer with no vips")
            val protoLB = buildAndStoreLB()

            Then("When we subscribe to the load-balancer observable")
            val lbMapper = new LoadBalancerMapper(protoLB.getId, vt)
            val observable = Observable.create(lbMapper)
            val obs = makeObservable()
            observable.subscribe(obs)

            Then("We obtain a simulation load-balancer with no vips")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            var simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf protoLB

            And("when we add a vip to the load-balancer")
            val vip1 = buildAndStoreVIP(protoLB.getId, "192.168.0.1")
            var updatedProtoLB = getLB(protoLB.getId)

            Then("We receive the load-balancer with one vip")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            assertVips(List(vip1), simLB.vips)

            And("When we update the vip")
            val updatedVip1 = vip1.toBuilder
                .setAdminStateUp(false)
                .build()
            store.update(updatedVip1)

            Then("We receive the load-balancer with the updated vip")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            assertVips(List(updatedVip1), simLB.vips)

            And("When we add a 2nd vip to the load-balancer")
            val vip2 = buildAndStoreVIP(protoLB.getId, "192.168.0.2")
            updatedProtoLB = getLB(protoLB.getId)

            Then("We receive the load-balancer with 2 vips")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents should have size 4
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            assertVips(List(updatedVip1, vip2), simLB.vips)

            When("We remove all vips from the load-balancer")
            updatedProtoLB = removeVipsFromLoadBalancer(updatedProtoLB)

            Then("We receive the load-balancer with no vips")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents should have size 6
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            assertVips(List.empty, simLB.vips)

            And("When we update one of the vips and the load-balancer")
            store.update(vip2.toBuilder
                             .setPoolId(UUID.randomUUID().asProto)
                             .build())
            store.update(updatedProtoLB.toBuilder
                             .setAdminStateUp(false)
                             .build())

            Then("We receive only one update")
            obs.awaitOnNext(7, timeout) shouldBe true
            obs.getOnNextEvents should have size 7
        }

        scenario("Deleting a load-balancer") {
            Given("A load-balancer")
            val protoLB = buildAndStoreLB()

            Then("When we subscribe to the load-balancer observable")
            val lbMapper = new LoadBalancerMapper(protoLB.getId, vt)
            val observable = Observable.create(lbMapper)
            val obs = makeObservable()
            observable.subscribe(obs)

            Then("We obtain a simulation load-balancer with no vips")
            obs.awaitOnNext(1, timeout) shouldBe true
            val simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf protoLB

            And("When we delete the load-balancer")
            store.delete(classOf[TopologyLB], protoLB.getId)

            Then("The mapper emits an on complete notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should have size 1
        }
    }

    private def assertVips(protoVips: List[TopologyVIP], simVips: Array[SimVIP])
    : Unit = {
        simVips should contain theSameElementsAs protoVips.map(
            ZoomConvert.fromProto(_, classOf[SimVIP])
        )
    }

    private def removeVipsFromLoadBalancer(loadBalancer: TopologyLB)
    : TopologyLB = {
        for (vId <- loadBalancer.getVipIdsList.asScala) {
            val vip = getVIP(vId).toBuilder.clearLoadBalancerId().build
            store.update(vip)
        }
        getLB(loadBalancer.getId)
    }

    private def getLB(lbId: UUID): TopologyLB =
        Await.result(store.get(classOf[TopologyLB], lbId), timeout)

    private def getVIP(vipId: UUID): TopologyVIP =
        Await.result(store.get(classOf[TopologyVIP], vipId), timeout)

    private def buildAndStoreVIP(lbId: UUID, ip: String): TopologyVIP = {
        val vip = createVIP(adminStateUp = Some(true),
                            poolId = Some(UUID.randomUUID()),
                            loadBalancerId = Some(lbId),
                            address = Some(ip),
                            protocolPort = Some(7777),
                            isStickySourceIp = Some(false))
        store.create(vip)
        vip
    }

    private def buildAndStoreLB(): TopologyLB = {
        val loadBalancer = createLoadBalancer(adminStateUp = Some(true))
        store.create(loadBalancer)
        loadBalancer
    }
}
