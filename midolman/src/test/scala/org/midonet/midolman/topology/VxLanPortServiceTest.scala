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

import org.midonet.cluster.data.storage.{StateResult, StateStorage, Storage}
import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.Vtep.Binding
import org.midonet.cluster.models.Topology.{Network, Vtep}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class VxLanPortServiceTest extends MidolmanSpec
                           with TopologyBuilder
                           with TopologyMatchers
                           with MidonetEventually {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var service: VxLanPortService = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
        service = new VxLanPortService(vt)
        service.startAsync().awaitRunning()
    }

    protected override def afterTest(): Unit = {
        service.stopAsync().awaitTerminated()
    }

    private def vtepConfig(vtepId: UUID): IPv4Addr = {
        val tunnelIp = IPv4Addr.random
        val vtepConfig = VtepConfiguration.newBuilder()
                                          .setVtepId(vtepId.asProto)
                                          .addTunnelAddresses(tunnelIp.asProto)
                                          .build()
        val obs = stateStore.setVtepConfig(vtepId, vtepConfig)
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

    private def ensureMapping(mappings: List[(IPv4Addr, Int, Option[UUID])])
    : Unit = {
        eventually {
            mappings.foreach(mapping => {
                val ip = mapping._1
                val vni = mapping._2
                val portId = mapping._3
                VxLanPortService.uuidOf(ip, vni) shouldBe portId
            })
        }
    }

    feature("VxLanPortMapper exposes a vxlan port map observable") {
        scenario("One logical switch") {
            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We can eventually retrieve the corresponding mapping")
            ensureMapping(List((tunnelIp, vni, Some(portId))))
        }

        scenario("The vtep is unbound from the network and bound again") {
            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()

            And("The vtep is unbound")
            store.update(network.toBuilder.clearVxlanPortIds().build())
            store.update(vtep.toBuilder.clearBindings().build())

            Then("Eventually the mapping is removed")
            val vni = network.getVni
            ensureMapping(List((tunnelIp, vni, None)))

            When("We rebind the vtep to the network")
            store.update(network)
            store.update(vtep)

            Then("Eventually the mapping is present again")
            val portId = network.getVxlanPortIds(0).asJava
            ensureMapping(List((tunnelIp, vni, Some(portId))))
        }

        scenario("Vteps bound to the same network") {
            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("Eventually the mapping can be retrieved")
            ensureMapping(List((tunnelIp, vni, Some(portId))))

            When("We bind a 2nd vtep to the network")
            val (network2, _, tunnelIp2) = addVtepToNetwork(network)
            val portId2 = network2.getVxlanPortIds(1).asJava

            Then("We receive the updated map")
            ensureMapping(List((tunnelIp, vni, Some(portId)),
                               (tunnelIp2, vni, Some(portId2))))

            When("We unbind the 1st vtep")
            val network3 = network2.toBuilder.removeVxlanPortIds(0).build()
            store.update(network3)
            store.update(vtep.toBuilder.clearBindings().build())

            Then("Eventually only the mapping for the 2nd vtep is present")
            ensureMapping(List((tunnelIp, vni, None),
                               (tunnelIp2, vni, Some(portId2))))

            When("We add a 3rd vtep to the network")
            val (network4, vtep3, tunnelIp3) = addVtepToNetwork(network3)
            val portId3 = network4.getVxlanPortIds(1).asJava

            Then("Eventually mappings for the 2nd and 3rd vtep are present")
            ensureMapping(List((tunnelIp2, vni, Some(portId2)),
                               (tunnelIp3, vni, Some(portId3))))

            When("We unbind the 3rd vtep")
            store.update(network4.toBuilder.removeVxlanPortIds(1).build())
            store.update(vtep3.toBuilder.clearBindings().build())

            Then("Eventually only the mapping for the 2nd vtep is present")
            ensureMapping(List((tunnelIp2, vni, Some(portId2)),
                               (tunnelIp3, vni, None)))
        }

        scenario("Two vteps bound to different networks") {
            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("Eventually the mapping is present in the map")
            ensureMapping(List((tunnelIp, vni, Some(portId))))

            When("We bind a 2nd vtep to a 2nd network")
            val (network2, vtep2, tunnelIp2) = oneLogicalSwitch()
            val vni2 = network2.getVni
            val portId2 = network2.getVxlanPortIds(0).asJava

            Then("Eventually the mappings for the two vteps are present")
            ensureMapping(List((tunnelIp, vni, Some(portId)),
                               (tunnelIp2, vni2, Some(portId2))))

            When("We unbind the 2nd vtep")
            store.update(vtep2.toBuilder.clearBindings().build())

            Then("Eventually only the mapping for the 1st vtep is present")
            ensureMapping(List((tunnelIp, vni, Some(portId)),
                               (tunnelIp2, vni2, None)))

            When("We unbind the 1st vtep")
            store.update(vtep.toBuilder.clearBindings().build())

            Then("Eventually both bindings are absent from the map")
            ensureMapping(List((tunnelIp, vni, None), (tunnelIp2, vni2, None)))
        }

        scenario("One vtep bound to two networks") {
            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("Eventually the mapping is present in the map")
            ensureMapping(List((tunnelIp, vni, Some(portId))))

            When("We bind the vtep to a 2nd network")
            val (network2, vtep2) = addNetworkToVtep(vtep)
            val vni2 = network2.getVni
            val portId2 = network2.getVxlanPortIds(0).asJava

            Then("Eventually both mappings are present in the map")
            ensureMapping(List((tunnelIp, vni, Some(portId)),
                               (tunnelIp, vni2, Some(portId2))))

            When("We unbind the vtep from the 2nd network")
            store.update(vtep2.toBuilder.removeBindings(1).build())

            Then("Eventually only the mapping for the 1st network is present")
            ensureMapping(List((tunnelIp, vni, Some(portId)),
                               (tunnelIp, vni2, None)))

            When("We unbind the vtep from the 1st network")
            store.update(vtep2.toBuilder.clearBindings().build())

            Then("Eventually none of the mappings are present")
            ensureMapping(List((tunnelIp, vni, None), (tunnelIp, vni2, None)))
        }
    }
}
