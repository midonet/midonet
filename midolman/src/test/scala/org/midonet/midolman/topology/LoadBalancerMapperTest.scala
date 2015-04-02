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
import org.midonet.cluster.data.storage.{NotFoundException, Storage, UpdateOp}
import org.midonet.cluster.models.Topology.Vip.SessionPersistence
import org.midonet.cluster.models.Topology.{LoadBalancer => TopologyLB, Vip => TopologyVip}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{LoadBalancer => SimLB, VIP => SimVIP}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec

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

    feature("The load-balancer mapper emits proper simulation objects") {
        scenario("The mapper emits error for non-existing load-balancers") {
            Given("A load-balancer identifier")
            val id = UUID.randomUUID

            And("A load-balancer mapper")
            val mapper = new LoadBalancerMapper(id, vt)

            And("An observer to the load-balancer mapper")
            val obs = new DeviceObserver[SimLB](vt)

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
            val obs = new DeviceObserver[SimLB](vt)
            observable.subscribe(obs)

            Then("We obtain a simulation load-balancer with no vips")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            var simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf protoLB

            And("When we add a vip to the load-balancer")
            val vip1 = buildAndStoreVip(protoLB.getId)
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
            val vip2 = buildAndStoreVip(protoLB.getId)
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
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents should have size 5
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
            assertVips(List.empty, simLB.vips)

            And("When we update one of the vips and the load-balancer")
            store.update(vip2.toBuilder
                             .clearLoadBalancerId()
                             .setPoolId(UUID.randomUUID().asProto)
                             .build())
            store.update(updatedProtoLB.toBuilder
                             .setAdminStateUp(false)
                             .build())

            Then("We receive only one update")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents should have size 6
        }

        scenario("Clearing the load-balancer id field in the VIP") {
            Given("A load-balancer with one vip")
            val protoLB = buildAndStoreLB()
            val vip = buildAndStoreVip(protoLB.getId)
            var updatedProtoLB = getLB(protoLB.getId)

            Then("When we subscribe to the load-balancer observable")
            val lbMapper = new LoadBalancerMapper(protoLB.getId, vt)
            val observable = Observable.create(lbMapper)
            val obs = new DeviceObserver[SimLB](vt)
            observable.subscribe(obs)

            Then("We obtain a simulation load-balancer with one vip")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            var simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB

            And("When we clear the load-balancer id field in the vip")
            val updatedVIP = vip.toBuilder.clearLoadBalancerId().build()
            store.update(updatedVIP)
            updatedProtoLB = getLB(protoLB.getId)

            Then("We obtain only one load-balancer with no vips")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
        }

        scenario("Updating the load-balancer with an identical object") {
            Given("A load-balancer")
            val protoLB = buildAndStoreLB()

            Then("When we subscribe to the load-balancer observable")
            val lbMapper = new LoadBalancerMapper(protoLB.getId, vt)
            val observable = Observable.create(lbMapper)
            val obs = new DeviceObserver[SimLB](vt)
            observable.subscribe(obs)

            Then("We obtain a simulation load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            var simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf protoLB

            And("When we update the load-balancer with the same object" +
                " and then we update its admin state")
            val updatedProtoLB = protoLB.toBuilder.setAdminStateUp(false)
                .build()
            store.multi(List(UpdateOp(protoLB), UpdateOp(updatedProtoLB)))

            Then("We obtain a single simulation load-balancer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
        }

        scenario("Updating the vip with an identical object") {
            Given("A load-balancer with one vip")
            val protoLB = buildAndStoreLB()
            val vip = buildAndStoreVip(protoLB.getId)
            val updatedProtoLB = getLB(protoLB.getId)

            Then("When we subscribe to the load-balancer observable")
            val lbMapper = new LoadBalancerMapper(protoLB.getId, vt)
            val observable = Observable.create(lbMapper)
            val obs = new DeviceObserver[SimLB](vt)
            observable.subscribe(obs)

            Then("We obtain a simulation load-balancer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            var simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB

            And("When we update the VIP with the same object" +
                " and then we update its admin state")
            val updatedVIP = vip.toBuilder.setAdminStateUp(false).build()
            store.multi(List(UpdateOp(vip), UpdateOp(updatedVIP)))

            Then("We obtain a single simulation load-balancer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            simLB = obs.getOnNextEvents.asScala.last
            simLB shouldBeDeviceOf updatedProtoLB
        }

        scenario("Deleting a load-balancer") {
            Given("A load-balancer")
            val protoLB = buildAndStoreLB()

            Then("When we subscribe to the load-balancer observable")
            val lbMapper = new LoadBalancerMapper(protoLB.getId, vt)
            val observable = Observable.create(lbMapper)
            val obs = new DeviceObserver[SimLB](vt)
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

    private def assertVips(protoVips: List[TopologyVip], simVips: Array[SimVIP])
    : Unit = {
        simVips should contain theSameElementsAs protoVips.map(
            ZoomConvert.fromProto(_, classOf[SimVIP])
        )
    }

    private def removeVipsFromLoadBalancer(loadBalancer: TopologyLB)
    : TopologyLB = {
        val updatedVips = for (vId <- loadBalancer.getVipIdsList.asScala)
            yield UpdateOp(getVip(vId).toBuilder.clearLoadBalancerId().build)
        store.multi(updatedVips)
        getLB(loadBalancer.getId)
    }

    private def getLB(lbId: UUID): TopologyLB =
        Await.result(store.get(classOf[TopologyLB], lbId), timeout)

    private def getVip(vipId: UUID): TopologyVip =
        Await.result(store.get(classOf[TopologyVip], vipId), timeout)

    private def buildAndStoreVip(lbId: UUID): TopologyVip = {
        val vip = createVip(adminStateUp = Some(true),
                            poolId = Some(UUID.randomUUID()),
                            loadBalancerId = Some(lbId),
                            sessionPersistence =
                                Some(SessionPersistence.SOURCE_IP))
        store.create(vip)
        vip
    }

    private def buildAndStoreLB(): TopologyLB = {
        val loadBalancer = createLoadBalancer(adminStateUp = Some(true))
        store.create(loadBalancer)
        loadBalancer
    }
}
