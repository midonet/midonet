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

import org.midonet.cluster.data.storage.{StateResult, Storage}
import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.Vtep.Binding
import org.midonet.cluster.models.Topology.{Network, Vtep}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.data.VtepStateStorage
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
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
    private var vtepStateStore: VtepStateStorage = _
    private var mapper: VxLanPortMapper = _
    private final val timeout = 5 seconds

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        val stateStore =
            injector.getInstance(classOf[MidonetBackend]).stateStore
        vtepStateStore = VtepStateStorage.asVtepStateStorage(stateStore)
        mapper = new VxLanPortMapper(vt)
    }

    private def vtepConfig(vtepId: UUID): IPv4Addr = {
        val tunnelIp = IPv4Addr.random
        val vtepConfig = VtepConfiguration.newBuilder()
                                          .setVtepId(vtepId.asProto)
                                          .addTunnelAddresses(tunnelIp.asProto)
                                          .build()
        val obs = vtepStateStore.setVtepConfig(vtepId, vtepConfig)
        val observer = new TestAwaitableObserver[StateResult]
        obs.subscribe(observer)
        observer.awaitOnNext(1, timeout) shouldBe true

        tunnelIp
    }

    private def oneLogicalSwitch(): (Network, Vtep, IPv4Addr) = {
        val ls = Random.nextInt(1 << 24)
        val portId = UUID.randomUUID()
        val vtepId = UUID.randomUUID()
        val port = createVxLanPort(id = portId, vtepId = Some(vtepId))
        store.create(port)
        val network = createBridge(vxlanPortIds = Set(portId),
                                   vni = Some(ls))
        store.create(network)
        val vtep = createVtep(id = vtepId,
                              networkId = Some(network.getId.asJava))
        store.create(vtep)
        val tunnelIp = vtepConfig(vtepId)

        (network, vtep, tunnelIp)
    }

    private def addVtepToNetwork(network: Network)
    : (Network, Vtep, IPv4Addr) = {
        val networkId = network.getId.asJava
        val vtepId = UUID.randomUUID()
        val portId = UUID.randomUUID()
        val port = createVxLanPort(id = portId,
                                   bridgeId = Some(networkId),
                                   vtepId = Some(vtepId))
        val network2 = network.toBuilder
                              .addVxlanPortIds(portId.asProto)
                              .build()

        val vtep = createVtep(id = vtepId,
                              networkId = Some(networkId))
        store.create(port)
        store.update(network2)
        store.create(vtep)
        val tunnelIp = vtepConfig(vtepId)

        (network2, vtep, tunnelIp)
    }

    private def addNetworkToVtep(vtep: Vtep): (Network, Vtep) = {
        val vtepId = vtep.getId.asJava
        val networkId = UUID.randomUUID()
        val ls = Random.nextInt(1 << 24)
        val portId = UUID.randomUUID()

        val port = createVxLanPort(id = portId,
                                   vtepId = Some(vtepId))
        store.create(port)

        val network = createBridge(id = networkId,
                                   vxlanPortIds = Set(portId),
                                   vni = Some(ls))
        store.create(network)

        val binding = Binding.newBuilder()
                             .setNetworkId(networkId.asProto)
                             .build()
        val vtep2 = vtep.toBuilder
                        .addBindings(binding)
                        .build()
        store.update(vtep2)

        (network, vtep2)
    }

    feature("VxLanPortMapper exposes a vxlan port map observable") {
        scenario("One logical switch") {
            Given("An observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive the map")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)
        }

        scenario("The vtep is unbound from the network and bound again") {
            Given("An observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()

            Then("We get notified")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1

            When("The vtep is unbound")
            store.update(network.toBuilder.clearVxlanPortIds().build())
            store.update(vtep.toBuilder.clearBindings().build())

            Then("We get notified with an empty map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) shouldBe Map.empty

            When("We rebind the vtep to the network")
            store.update(network)
            store.update(vtep)

            Then("We receive a map with one entry")
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive the map")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(2) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)
        }

        scenario("Vteps bound to the same network") {
            Given("One observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We bind a 2nd vtep to the network")
            val (network2, _, tunnelIp2) = addVtepToNetwork(network)
            val portId2 = network2.getVxlanPortIds(1).asJava

            Then("We receive the updated map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId, (tunnelIp2, vni) -> portId2)

            When("We unbind the 1st vtep")
            val network3 = network2.toBuilder.removeVxlanPortIds(0).build()
            store.update(network3)
            store.update(vtep.toBuilder.clearBindings().build())

            Then("We receive a map with information about the 2nd vtep only")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(2) should contain theSameElementsAs
                Map((tunnelIp2, vni) -> portId2)

            When("We add a 3rd vtep to the network")
            val (network4, vtep3, tunnelIp3) = addVtepToNetwork(network3)
            val portId3 = network4.getVxlanPortIds(1).asJava

            Then("We receive a map with two entries")
            observer.awaitOnNext(4, timeout) shouldBe true
            observer.getOnNextEvents should have size 4
            observer.getOnNextEvents.get(3) should contain theSameElementsAs
                Map((tunnelIp2, vni) -> portId2, (tunnelIp3, vni) -> portId3)

            When("We unbind the 3rd vtep")
            store.update(network4.toBuilder.removeVxlanPortIds(1).build())
            store.update(vtep3.toBuilder.clearBindings().build())

            Then("We get notified with a mapping for the 2nd vtep only")
            observer.awaitOnNext(5, timeout) shouldBe true
            observer.getOnNextEvents should have size 5
            observer.getOnNextEvents.get(4) should contain theSameElementsAs
                Map((tunnelIp2, vni) -> portId2)
        }

        scenario("Two vteps bound to different networks") {
            Given("One observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We bind a 2nd vtep to a 2nd network")
            val (network2, vtep2, tunnelIp2) = oneLogicalSwitch()
            val vni2 = network2.getVni
            val portId2 = network2.getVxlanPortIds(0).asJava

            Then("We receive the updated map")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId, (tunnelIp2, vni2) -> portId2)

            When("We unbind the 2nd vtep")
            store.update(vtep2.toBuilder.clearBindings().build())

            Then("We receive the updated map")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(2) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We unbind the 1st vtep")
            store.update(vtep.toBuilder.clearBindings().build())

            Then("We receive an empty map")
            observer.awaitOnNext(4, timeout) shouldBe true
            observer.getOnNextEvents should have size 4
            observer.getOnNextEvents.get(3) shouldBe Map.empty
        }

        scenario("One vtep bound to two networks") {
            Given("One observer subscribed to the mapper's observable")
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]()
            mapper.observable.subscribe(observer)

            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We bind the vtep to a 2nd network")
            val (network2, vtep2) = addNetworkToVtep(vtep)
            val vni2 = network2.getVni
            val portId2 = network2.getVxlanPortIds(0).asJava

            Then("We receive the map with two entries")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents should have size 2
            observer.getOnNextEvents.get(1) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId, (tunnelIp, vni2) -> portId2)

            When("We unbind the vtep from the 2nd network")
            store.update(vtep2.toBuilder.removeBindings(1).build())

            Then("We receive the map with one entry")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(2) should contain theSameElementsAs
                Map((tunnelIp, vni) -> portId)

            When("We unbind the vtep from the 1st network")
            store.update(vtep2.toBuilder.clearBindings().build())

            Then("We receive an empty map")
            observer.awaitOnNext(4, timeout) shouldBe true
            observer.getOnNextEvents should have size 4
            observer.getOnNextEvents.get(3) shouldBe Map.empty
        }
    }

    feature("Companion object") {
        scenario("uuidOf") {
            Given("An empty topology and a stopped VxlanPortMapper")

            When("We query for a random tunnel IP-vni pair")
            val tunnelIp = IPv4Addr.random
            val vni = Random.nextInt(1 << 24)

            Then("The mapping does not exist")
            VxLanPortMapper.uuidOf(tunnelIp, vni) shouldBe None

            When("We start the mapper")
            val observable = VxLanPortMapper.start(vt)
            val observer = new TestAwaitableObserver[Map[TunnelIpAndVni, UUID]]
            observable.subscribe(observer)

            Then("A query for the same tunnel IP-vni pair returns None")
            VxLanPortMapper.uuidOf(tunnelIp, vni) shouldBe None

            When("We create one logical switch")
            val (network, vtep, tunnelIp2) = oneLogicalSwitch()

            And("We wait for the update to be notified")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents should have size 1

            Then("A query for the created tunnelIP-vni pair returns the correct port")
            val vni2 = network.getVni
            val portId = network.getVxlanPortIds(0).asJava

            VxLanPortMapper.uuidOf(tunnelIp2, vni2) shouldBe Some(portId)
        }
    }
}
