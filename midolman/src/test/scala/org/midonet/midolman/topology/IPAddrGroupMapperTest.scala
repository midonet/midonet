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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{IPAddrGroup => TopologyIPAddrGroup}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{IPAddrGroup => SimAddrGroup}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPAddr

@RunWith(classOf[JUnitRunner])
class IPAddrGroupMapperTest extends MidolmanSpec with TopologyBuilder
                             with TopologyMatchers {

    import TopologyBuilder._

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    feature("The ipAddrGroup mapper emits IpAddrGroup objects") {
        scenario("The mapper emits error for non-existing ipAddrGroups") {
            Given("An ipAddrGroup identifier")
            val id = UUID.randomUUID

            And("An ipAddrGroup mapper")
            val mapper = new IPAddrGroupMapper(id, vt)

            And("An observer to the ipAddrGroup mapper")
            val obs = new DeviceObserver[SimAddrGroup](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents should have size 1
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyIPAddrGroup]
            e.id shouldBe id
        }

        scenario("The mapper emits an existing IpAddrGroup") {
            Given("An ipAddrGroup")
            val ipAddrGroup = buildAndStoreIpAddrGroup()

            And("An ipAddrGroup mapper")
            val mapper = new IPAddrGroupMapper(ipAddrGroup.getId.asJava, vt)

            And("An observer to the ipAddrGroup mapper")
            val obs = new DeviceObserver[SimAddrGroup](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see the ipAddrGroup")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            val simIpAddrGroup = obs.getOnNextEvents.asScala.last
            simIpAddrGroup shouldBeDeviceOf ipAddrGroup


            When("When we update the IpAddrGroup")
            var updatedProto = addIpToIPAddrGroup(ipAddrGroup, "192.168.0.1",
                               Set(UUID.randomUUID()))

            Then("We receive the updated IPAddrGroup")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2

            var updatedSimIpAddrGroup = obs.getOnNextEvents.asScala.last
            updatedSimIpAddrGroup shouldBeDeviceOf updatedProto

            When("We remove the ip from the IPAddrGroup")
            updatedProto = removeAllIps(updatedProto)

            Then("We receive the IPAddrGroup with no IPs")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            updatedSimIpAddrGroup = obs.getOnNextEvents.asScala.last
            updatedSimIpAddrGroup shouldBeDeviceOf updatedProto
        }

        scenario("The mapper completes on IPAddrGroup delete") {
            Given("An IPAddrGroup")
            val ipAddrGroup = buildAndStoreIpAddrGroup()

            And("An ipAddrGroup mapper")
            val mapper = new IPAddrGroupMapper(ipAddrGroup.getId.asJava, vt)

            And("An observer to the ipAddrGroup mapper")
            val obs = new DeviceObserver[SimAddrGroup](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see the ipAddrGroup")
            obs.awaitOnNext(1, timeout) shouldBe true

            And("When we delete the IPAddrGroup")
            store.delete(classOf[TopologyIPAddrGroup], ipAddrGroup.getId)

            Then("The observer receives an onComplete notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should have size 1
        }
    }

    private def removeAllIps(ipAddrGroup: TopologyIPAddrGroup)
    : TopologyIPAddrGroup = {

        val updatedIPAddrGroup = ipAddrGroup.toBuilder
            .clearIpAddrPorts()
            .build()
        store.update(updatedIPAddrGroup)
        updatedIPAddrGroup
    }

    private def addIpToIPAddrGroup(ipAddrGroup: TopologyIPAddrGroup,
                                   ip: String, ports: Set[UUID])
    : TopologyIPAddrGroup = {
        val updatedIpAddrGroup =
            ipAddrGroup.addIpAddrPort(IPAddr.fromString(ip), ports)
        store.update(updatedIpAddrGroup)
        updatedIpAddrGroup
    }

    private def buildAndStoreIpAddrGroup(): TopologyIPAddrGroup = {
        val ipAddrGroup = createIpAddrGroup()
        store.create(ipAddrGroup)
        ipAddrGroup
    }
}
