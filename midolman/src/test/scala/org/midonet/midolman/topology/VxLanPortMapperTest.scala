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

package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Topology.{Network, Vtep}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.midolman.topology.VxLanPortMapper.TunnelIpAndVni
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class VxLanPortMapperTest extends MidolmanSpec
                          with TopologyBuilder
                          with TopologyMatchers
                          with MidonetEventually {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var mapper: VxLanPortMapper = _
    private final val timeout = 5 seconds

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        mapper = new VxLanPortMapper(vt)
    }

    private def oneLogicalSwitch(): (Network, Vtep) = {
        val ls = Random.nextInt(1 << 24)
        val portId = UUID.randomUUID()
        val vtepId = UUID.randomUUID()
        val port = createVxLanPort(id = portId, vtepId = Some(vtepId))
        store.create(port)
        val network = createBridge(vxlanPortIds = Set(portId),
                                   vni = Some(ls))
        store.create(network)
        val tunnelIp = IPv4Addr.random
        val vtep = createVtep(id = vtepId,
                              networkId = Some(network.getId.asJava),
                              tunnelIp = Some(tunnelIp))
        store.create(vtep)
        (network, vtep)
    }

    feature("VxLanPortMapper exposes a vxlan port map observable") {
        scenario("Adding/removing vxlan ports with one vtep") {
            Given("An observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep) = oneLogicalSwitch()
            val tunnelIp = IPv4Addr.fromString(vtep.getTunnelIpsList.get(0))
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive the map")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We remove the vxlan port")
            val network2 = network.toBuilder
                                  .clearVxlanPortIds()
                                  .build()
            store.update(network2)

            Then("We receive the updated map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) shouldBe Map.empty

            When("We add a new vxlan port")
            val networkId = network.getId.asJava
            val vtepId = vtep.getId.asJava
            val port2 = createVxLanPort(bridgeId = Some(networkId),
                                        vtepId = Some(vtepId))
            store.create(port2)
            val network3 = network2.toBuilder
                                   .addVxlanPortIds(port2.getId)
                                   .build()
            store.update(network3)

            Then("We receive the updated map")
            val portId2 = port2.getId.asJava
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(2) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId2)

        }

        scenario("Deleting the network") {
            Given("An observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep) = oneLogicalSwitch()
            val tunnelIp = IPv4Addr.fromString(vtep.getTunnelIpsList.get(0))
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We get notified")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1

            When("The network gets deleted")
            store.delete(classOf[Network], network.getId.asJava)
            store.update(vtep.toBuilder.clearBindings().build())

            Then("We get notified with an empty map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) shouldBe Map.empty
        }

        scenario("Deleting a Vtep") {
            Given("One observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep) = oneLogicalSwitch()
            val tunnelIp = IPv4Addr.fromString(vtep.getTunnelIpsList.get(0))
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We delete the Vtep")
            store.delete(classOf[Vtep], vtep.getId.asJava)

            Then("We get notified with an empty map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) shouldBe Map.empty
        }

        scenario("Two vteps bound to the same network") {
            Given("One observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep) = oneLogicalSwitch()
            val tunnelIp = IPv4Addr.fromString(vtep.getTunnelIpsList.get(0))
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We bind a 2nd vtep to the network")
            val networkId = network.getId.asJava
            val vtepId2 = UUID.randomUUID()
            val portId2 = UUID.randomUUID()
            val port2 = createVxLanPort(id = portId2,
                                        bridgeId = Some(networkId),
                                        vtepId = Some(vtepId2))
            val network2 = network.toBuilder
                                  .addVxlanPortIds(portId2.asProto)
                                  .build()
            val tunnelIp2 = IPv4Addr.random
            val vtep2 = createVtep(id = vtepId2,
                                   networkId = Some(networkId),
                                   tunnelIp = Some(tunnelIp2))
            store.create(port2)
            store.update(network2)
            store.create(vtep2)

            Then("We receive the updated map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId, (tunnelIp2, vni) -> portId2)

            When("We delete the 1st vtep")
            val vtepId = vtep.getId.asJava
            store.delete(classOf[Vtep], vtepId)

            Then("We receive a map with information about the 2nd vtep only")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(2) should contain theSameElementsAs
                Map((tunnelIp2, vni) -> portId2)
        }

        scenario("Two vteps bound to different networks") {
            Given("One observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep) = oneLogicalSwitch()
            val tunnelIp = IPv4Addr.fromString(vtep.getTunnelIpsList.get(0))
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We bind a 2nd vtep to a 2nd network")
            val (network2, vtep2) = oneLogicalSwitch()
            val tunnelIp2 = IPv4Addr.fromString(vtep2.getTunnelIps(0))
            val vni2 = network2.getVni
            val portId2 = network2.getVxlanPortIds(0).asJava

            Then("We receive the updated map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId, (tunnelIp2, vni2) -> portId2)

            When("We delete the 2nd vtep")
            store.delete(classOf[Vtep], vtep2.getId.asJava)

            Then("We receive the updated map")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(2) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)
        }
    }

//    feature("Companion object") {
//        scenario("uuidOf") {
//            Given("An empty topology and a stopped VxlanPortMapper")
//
//            When("We query for a random tunnel IP-vni pair")
//            val ip = IPv4Addr.random
//            val vni = Random.nextInt(1 << 24)
//
//            Then("The mapping does not exist")
//            VxLanPortMapper.uuidOf(ip, vni) shouldBe None
//
//            When("We start the mapper")
//            val observable = VxLanPortMapper.start(vt)
//            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]
//            observable.subscribe(observer)
//
//            Then("A query for the same tunnel IP-vni pair returns None")
//            VxLanPortMapper.uuidOf(ip, vni) shouldBe None
//
//            When("We create one logical switch")
//            val (network, vtep) = oneLogicalSwitch()
//
//            And("We wait for the update to be notified")
//            observer.awaitOnNext(1, timeout) shouldBe true
//
//            Then("A query for the created tunnelIP-vni pair returns the correct port")
//            val ip2 = IPv4Addr.fromString(vtep.getTunnelIps(0))
//            val vni2 = network.getVni
//            val portId = network.getVxlanPortIds(0).asJava
//
//            VxLanPortMapper.uuidOf(ip2, vni2) shouldBe Some(portId)
//        }
//    }
}
