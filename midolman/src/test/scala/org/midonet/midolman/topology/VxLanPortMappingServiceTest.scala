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
import java.util.UUID.randomUUID

import scala.concurrent.duration._
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology
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
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class VxLanPortMappingServiceTest extends MidolmanSpec
                                  with TopologyBuilder
                                  with TopologyMatchers
                                  with MidonetEventually {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var service: VxLanPortMappingService = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
        service = new VxLanPortMappingService(vt)
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
        val portId = randomUUID()
        val vtepId = randomUUID()
        val port = createVxLanPort(id = portId, vtepId = Some(vtepId))
        store.create(port)
        val network = createBridge(vxlanPortIds = Set(portId),
                                   vni = Some(ls))
        store.create(network)
        val vtep = createVtepOneBinding(id = vtepId,
                                        networkId = Some(network.getId.asJava))
        store.create(vtep)
        val tunnelIp = vtepConfig(vtepId)

        (network, vtep, tunnelIp)
    }

    private def addVtepToNetwork(network: Network)
    : (Network, Vtep, IPv4Addr) = {
        val updatedNetwork = store.get(classOf[Network], network.getId).await()
        val networkId = updatedNetwork.getId.asJava
        val vtepId = randomUUID()
        val portId = randomUUID()
        val port = createVxLanPort(id = portId,
                                   bridgeId = Some(networkId),
                                   vtepId = Some(vtepId))
        val network2 = updatedNetwork.toBuilder
                              .addVxlanPortIds(portId.asProto)
                              .build()

        val vtep = createVtepOneBinding(id = vtepId,
                                        networkId = Some(networkId))
        store.create(port)
        store.update(network2)
        store.create(vtep)
        val tunnelIp = vtepConfig(vtepId)

        (network2, vtep, tunnelIp)
    }

    private def addNetworkToVtep(vtep: Vtep): (Network, Vtep) = {
        val vtepId = vtep.getId.asJava
        val networkId = randomUUID()
        val ls = Random.nextInt(1 << 24)
        val portId = randomUUID()

        val port = createVxLanPort(id = portId,
                                   bridgeId = Some(networkId),
                                   vtepId = Some(vtepId))
        val network = createBridge(id = networkId,
                                   vxlanPortIds = Set(portId),
                                   vni = Some(ls))
        val binding = Binding.newBuilder().setNetworkId(networkId.asProto)
                                          .setPortName("eth0")
                                          .setVlanId(0)
                                          .build()
        val updatedVtep = vtep.toBuilder.addBindings(binding).build()

        store.multi(Seq(CreateOp(network), CreateOp(port),
                        UpdateOp(updatedVtep)))

        (network, updatedVtep)
    }

    private def ensureMapping(mappings: List[(IPv4Addr, Int, Option[UUID])])
    : Unit = {
        eventually {
            mappings.foreach(mapping => {
                val ip = mapping._1
                val vni = mapping._2
                val portId = mapping._3
                VxLanPortMappingService.uuidOf(ip, vni) shouldBe portId
            })
        }
    }

    feature("VxLanPortMapper exposes a vxlan port map observable") {
        scenario("One logical switch") {
            When("We create a topology with one logical switch")
            val (network, _, tunnelIp) = oneLogicalSwitch()
            val vni = network.getVni
            val portId = network.getVxlanPortIdsList.get(0).asJava

            Then("We can eventually retrieve the corresponding mapping")
            ensureMapping(List((tunnelIp, vni, Some(portId))))
        }

        scenario("The VTEP is unbound from the network and bound again") {
            When("We create a topology with one logical switch")
            val (network, vtep, tunnelIp) = oneLogicalSwitch()

            And("The VTEP is unbound")
            store.update(network.toBuilder.clearVxlanPortIds().build())
            store.update(vtep.toBuilder.clearBindings().build())

            Then("Eventually the mapping is removed")
            val vni = network.getVni
            ensureMapping(List((tunnelIp, vni, None)))

            When("We rebind the VTEP to the network")
            store.update(network)
            store.update(vtep)

            Then("Eventually the mapping is present again")
            val portId = network.getVxlanPortIds(0).asJava
            ensureMapping(List((tunnelIp, vni, Some(portId))))
        }

        scenario("VTEPs bound to the same network") {
            When("We create a topology with one logical switch")
            val (network, vtep1, tunnelIp1) = oneLogicalSwitch()
            val vni = network.getVni
            val port1Id = network.getVxlanPortIdsList.get(0).asJava

            Then("Eventually the mapping can be retrieved")
            ensureMapping(List((tunnelIp1, vni, Some(port1Id))))

            When("We bind a 2nd VTEP to the network")
            val (network2, _, tunnelIp2) = addVtepToNetwork(network)
            val port2Id = network2.getVxlanPortIds(1).asJava

            Then("We receive the updated map")
            ensureMapping(List((tunnelIp1, vni, Some(port1Id)),
                               (tunnelIp2, vni, Some(port2Id))))

            When("We unbind the 1st VTEP")
            store.multi(Seq(
                UpdateOp(network2.toBuilder.removeVxlanPortIds(0).build()),
                DeleteOp(classOf[Topology.Port], port1Id),
                UpdateOp(vtep1.toBuilder.removeBindings(0).build())
            ))

            Then("Eventually only the mapping for the 2nd VTEP is present")
            ensureMapping(List((tunnelIp1, vni, None),
                               (tunnelIp2, vni, Some(port2Id))))

            When("We add a 3rd VTEP to the network")
            val (network3, vtep3, tunnelIp3) = addVtepToNetwork(network)
            val port3Id = network3.getVxlanPortIds(1).asJava

            Then("Eventually mappings for the 2nd and 3rd VTEP are present")
            ensureMapping(List((tunnelIp2, vni, Some(port2Id)),
                               (tunnelIp3, vni, Some(port3Id))))

            When("We unbind the 3rd VTEP")
            store.multi(Seq(
                UpdateOp(network3.toBuilder.removeVxlanPortIds(1).build()),
                DeleteOp(classOf[Topology.Port], port3Id),
                UpdateOp(vtep3.toBuilder.clearBindings().build())
            ))

            Then("Eventually only the mapping for the 2nd VTEP is present")
            ensureMapping(List((tunnelIp2, vni, Some(port2Id)),
                               (tunnelIp3, vni, None)))
        }

        scenario("Two VTEPs bound to different networks") {
            When("We create a topology with one logical switch")
            val (network1, vtep1, tunnelIp1) = oneLogicalSwitch()
            val vni1 = network1.getVni
            val port1Id = network1.getVxlanPortIdsList.get(0).asJava

            Then("Eventually the mapping is present in the map")
            ensureMapping(List((tunnelIp1, vni1, Some(port1Id))))

            When("We bind a 2nd VTEP to a 2nd network")
            val (network2, vtep2, tunnelIp2) = oneLogicalSwitch()
            val vni2 = network2.getVni
            val port2Id = network2.getVxlanPortIds(0).asJava

            Then("Eventually the mappings for the two VTEPs are present")
            ensureMapping(List((tunnelIp1, vni1, Some(port1Id)),
                               (tunnelIp2, vni2, Some(port2Id))))

            When("We unbind the 2nd VTEP")
            store.multi(Seq(
                UpdateOp(network2.toBuilder.clearVxlanPortIds().build()),
                DeleteOp(classOf[Topology.Port], port2Id),
                UpdateOp(vtep2.toBuilder.clearBindings().build())
            ))

            Then("Eventually only the mapping for the 1st VTEP is present")
            ensureMapping(List((tunnelIp1, vni1, Some(port1Id)),
                               (tunnelIp2, vni2, None)))

            When("We unbind the 1st VTEP")
            store.multi(Seq(
                UpdateOp(network1.toBuilder.clearVxlanPortIds().build()),
                DeleteOp(classOf[Topology.Port], port1Id),
                UpdateOp(vtep1.toBuilder.clearBindings().build())
            ))

            Then("Eventually both bindings are absent from the map")
            ensureMapping(List((tunnelIp1, vni1, None),
                               (tunnelIp2, vni2, None)))
        }

        scenario("One VTEP bound to two networks") {
            When("We create a topology with one logical switch")
            val (network1, vtep, tunnelIp) = oneLogicalSwitch()
            val vni1 = network1.getVni
            val port1Id = network1.getVxlanPortIdsList.get(0).asJava

            Then("Eventually the mapping is present in the map")
            ensureMapping(List((tunnelIp, vni1, Some(port1Id))))

            When("We bind the VTEP to a 2nd network")
            val (network2, vtep2) = addNetworkToVtep(vtep)
            val vni2 = network2.getVni
            val port2Id = network2.getVxlanPortIds(0).asJava

            Then("Eventually both mappings are present in the map")
            ensureMapping(List((tunnelIp, vni1, Some(port1Id)),
                               (tunnelIp, vni2, Some(port2Id))))

            When("We unbind the VTEP from the 2nd network")
            store.multi(Seq(
                UpdateOp(network2.toBuilder.clearVxlanPortIds().build()),
                DeleteOp(classOf[Topology.Port], port2Id),
                UpdateOp(vtep2.toBuilder.removeBindings(1).build())
            ))

            Then("Eventually only the mapping for the 1st network is present")
            ensureMapping(List((tunnelIp, vni1, Some(port1Id)),
                               (tunnelIp, vni2, None)))

            When("We unbind the VTEP from the 1st network")
            store.multi(Seq(
                UpdateOp(network1.toBuilder.clearVxlanPortIds().build()),
                DeleteOp(classOf[Topology.Port], port1Id),
                UpdateOp(vtep2.toBuilder.clearBindings().build())
            ))

            Then("Eventually none of the mappings are present")
            ensureMapping(List((tunnelIp, vni1, None), (tunnelIp, vni2, None)))
        }
    }
}
