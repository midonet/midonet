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

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.{IPVersion, IPAddress}
import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.Vtep.Binding
import org.midonet.cluster.models.Topology.{Port, Network, Vtep}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VxLanPortMappingService.{TunnelInfo, portOf, tunnelOf}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class VxLanPortMappingServiceTest extends MidolmanSpec
                                  with TopologyBuilder
                                  with TopologyMatchers {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var service: VxLanPortMappingService = _
    private final val timeout = 5 seconds
    private val random = new Random()

    protected override def beforeTest(): Unit = {
        InMemoryStorage.NamespaceId = MidonetBackend.ClusterNamespaceId

        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
        service = new VxLanPortMappingService(vt)
        service.startAsync().awaitRunning()
    }

    protected override def afterTest(): Unit = {
        service.stopAsync().awaitTerminated()
    }

    private def addVtep(vtepId: UUID = UUID.randomUUID,
                        tunnelIps: Seq[IPv4Addr] = Seq(IPv4Addr.random))
    : (UUID, IPv4Addr, UUID) = {
        val vtep = createVtep(id = vtepId)
        store create vtep
        setTunnelIps(vtepId, tunnelIps: _*)
        (vtepId, tunnelIps.head, vtep.getTunnelZoneId.asJava)
    }

    private def setTunnelIps(vtepId: UUID, tunnelIps: IPv4Addr*): Unit = {
        val vtepConfig = VtepConfiguration.newBuilder()
            .setVtepId(vtepId.asProto)
            .addAllTunnelAddresses(tunnelIps.map(_.asProto).asJava)
            .build()
        stateStore.setVtepConfig(vtepId, vtepConfig).await(timeout)
    }

    private def corruptTunnelIp(vtepId: UUID): Unit = {
        val vtepConfig = VtepConfiguration.newBuilder()
            .setVtepId(vtepId.asProto)
            .addTunnelAddresses(IPAddress.newBuilder()
                                    .setVersion(IPVersion.V4)
                                    .setAddress("invalid-ip")
                                    .build())
            .build()
        stateStore.setVtepConfig(vtepId, vtepConfig).await(timeout)
    }

    private def addNetwork(networkId: UUID = UUID.randomUUID,
                           vni: Int = random.nextInt(1 << 24))
    : (UUID, Int) = {
        val network = createBridge(id = networkId,
                                   vni = Some(vni))
        store create network
        (networkId, vni)
    }

    private def addFirstBinding(vtepId: UUID, networkId: UUID, name: String)
    : UUID = {
        val port = createVxLanPort(bridgeId = Some(networkId),
                                   vtepId = Some(vtepId))
        val network = store.get(classOf[Network], networkId).await(timeout)
            .toBuilder
            .addVxlanPortIds(port.getId)
            .build()
        val vtep = store.get(classOf[Vtep], vtepId).await(timeout)
                        .toBuilder
                        .addBindings(Binding.newBuilder()
                                            .setNetworkId(networkId.asProto)
                                            .setPortName(name)
                                            .build())
                        .build()
        store.multi(Seq(CreateOp(port), UpdateOp(network), UpdateOp(vtep)))
        port.getId
    }

    private def addNextBinding(vtepId: UUID, networkId: UUID, name: String)
    : Unit = {
        val vtep = store.get(classOf[Vtep], vtepId).await(timeout)
                        .toBuilder
                        .addBindings(Binding.newBuilder()
                                            .setNetworkId(networkId.asProto)
                                            .setPortName(name)
                                            .build())
                        .build()
        store update vtep
    }

    private def removeFirstBinding(vtepId: UUID, networkId: UUID, name: String)
    : Unit = {
        val vtep = store.get(classOf[Vtep], vtepId).await(timeout)
        store update vtep.toBuilder
                         .clearBindings()
                         .addAllBindings(vtep.getBindingsList.asScala
                                             .filterNot(p => {
                                                 p.getNetworkId.asJava == networkId &&
                                                 p.getPortName == name
                                             })
                                             .asJava)
                         .build()
    }

    private def removeLastBinding(vtepId: UUID, networkId: UUID, portId: UUID,
                                  name: String): Unit = {
        val oldNet = store.get(classOf[Network], networkId).await(timeout)
        val newNet = oldNet.toBuilder
            .clearVxlanPortIds()
            .addAllVxlanPortIds(oldNet.getVxlanPortIdsList.asScala
                                    .filterNot(_.asJava == portId)
                                    .asJava)
            .build()
        val oldVtep = store.get(classOf[Vtep], vtepId).await(timeout)
        val newVtep = oldVtep.toBuilder
                             .clearBindings()
                             .addAllBindings(oldVtep.getBindingsList.asScala
                                                    .filterNot(p => {
                                                        p.getNetworkId.asJava == networkId &&
                                                        p.getPortName == name
                                                    })
                                                    .asJava)
                             .build()
        store.multi(Seq(UpdateOp(newNet), DeleteOp(classOf[Port], portId),
                        UpdateOp(newVtep)))
    }


    feature("Mapper returns None for non-existing keys") {
        scenario("Non-existing VNI") {
            Given("A VTEP")
            val (vtepId, tunnelIp, tzoneId) = addVtep()

            Then("The mapper returns none for a random VNI")
            portOf(IPv4Addr.random, random.nextInt()) shouldBe None
        }

        scenario("Non-existing tunnel IP") {
            Given("A VTEP with a network binding")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId = addFirstBinding(vtepId, networkId, "port0")

            Then("The mapper returns none for a random tunnel IP")
            portOf(IPv4Addr.random, vni) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni))
        }
    }

    feature("Mapper processes VTEP updates") {
        scenario("A VTEP with a network binding") {
            Given("A VTEP with a network binding")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId = addFirstBinding(vtepId, networkId, "port0")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni) shouldBe Some(portId)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni))
        }

        scenario("Adding bindings") {
            Given("A VTEP with a binding")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val portId1 = addFirstBinding(vtepId, networkId1, "port1")

            When("Adding a binding to a second network")
            val (networkId2, vni2) = addNetwork()
            val portId2 = addFirstBinding(vtepId, networkId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni1) shouldBe Some(portId1)
            portOf(tunnelIp, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni1))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni2))

            When("Adding a binding to a third network")
            val (networkId3, vni3) = addNetwork()
            val portId3 = addFirstBinding(vtepId, networkId3, "port3")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni1) shouldBe Some(portId1)
            portOf(tunnelIp, vni2) shouldBe Some(portId2)
            portOf(tunnelIp, vni3) shouldBe Some(portId3)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni1))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni2))
            tunnelOf(portId3) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni3))
        }

        scenario("Removing binding") {
            Given("A VTEP with two bindings")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val (networkId2, vni2) = addNetwork()
            val portId1 = addFirstBinding(vtepId, networkId1, "port1")
            val portId2 = addFirstBinding(vtepId, networkId2, "port2")

            When("Removing the first binding")
            removeLastBinding(vtepId, networkId1, portId1, "port1")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni1) shouldBe None
            portOf(tunnelIp, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni2))

            When("Removing the second binding")
            removeLastBinding(vtepId, networkId2, portId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni1) shouldBe None
            portOf(tunnelIp, vni2) shouldBe None
        }

        scenario("Replace bindings") {
            Given("A VTEP with two bindings")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val (networkId2, vni2) = addNetwork()
            val portId1 = addFirstBinding(vtepId, networkId1, "port1")
            val portId2 = addFirstBinding(vtepId, networkId2, "port2")

            When("Replacing the bindings with other two bindings")
            removeLastBinding(vtepId, networkId1, portId1, "port1")
            removeLastBinding(vtepId, networkId2, portId2, "port2")
            val (networkId3, vni3) = addNetwork()
            val (networkId4, vni4) = addNetwork()
            val portId3 = addFirstBinding(vtepId, networkId3, "port1")
            val portId4 = addFirstBinding(vtepId, networkId4, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni1) shouldBe None
            portOf(tunnelIp, vni2) shouldBe None
            portOf(tunnelIp, vni3) shouldBe Some(portId3)
            portOf(tunnelIp, vni4) shouldBe Some(portId4)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None
            tunnelOf(portId3) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni3))
            tunnelOf(portId4) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni4))
        }

        scenario("Add multiple bindings to the same network") {
            Given("A VTEP with a binding")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId = addFirstBinding(vtepId, networkId, "port1")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni) shouldBe Some(portId)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni))

            When("Adding a second binding to the same network")
            addNextBinding(vtepId, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni) shouldBe Some(portId)
        }

        scenario("Remove multiple bindings from the same network") {
            Given("A VTEP with two binding")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId = addFirstBinding(vtepId, networkId, "port1")
            addNextBinding(vtepId, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni) shouldBe Some(portId)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni))

            When("Removing the first binding")
            removeFirstBinding(vtepId, networkId, "port1")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni) shouldBe Some(portId)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe Some(TunnelInfo(tunnelIp, tzoneId, vni))

            When("Removing the last binding")
            removeLastBinding(vtepId, networkId, portId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp, vni) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe None
        }

        scenario("Updating the VTEP tunnel IP") {
            Given("A VTEP with a network binding")
            val (vtepId, tunnelIp1, tzoneId) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId = addFirstBinding(vtepId, networkId, "port0")

            Then("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId, vni))

            When("Changing the tunnel IP")
            val tunnelIp2 = IPv4Addr.random
            setTunnelIps(vtepId, tunnelIp2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe Some(portId)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId, vni))
        }

        scenario("Adding a VTEP with binding to the same network") {
            Given("A VTEP with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId, "port1")

            Then("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni))

            When("Adding a second VTEP")
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val portId2 = addFirstBinding(vtepId2, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe Some(portId1)
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("Adding a third VTEP")
            val (vtepId3, tunnelIp3, tzoneId3) = addVtep()
            val portId3 = addFirstBinding(vtepId3, networkId, "port3")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe Some(portId1)
            portOf(tunnelIp2, vni) shouldBe Some(portId2)
            portOf(tunnelIp3, vni) shouldBe Some(portId3)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))
            tunnelOf(portId3) shouldBe Some(TunnelInfo(tunnelIp3, tzoneId3, vni))
        }

        scenario("Adding a VTEP with a binding to a different network") {
            Given("A VTEP with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId1, "port1")

            Then("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni1))

            When("Adding a second VTEP")
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val (networkId2, vni2) = addNetwork()
            val portId2 = addFirstBinding(vtepId2, networkId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe Some(portId1)
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)
            portOf(tunnelIp1, vni2) shouldBe None
            portOf(tunnelIp2, vni1) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni1))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))
        }

        scenario("Deleting only VTEPs bound to the same network") {
            Given("Two VTEPs with a network binding")
            val (networkId, vni) = addNetwork()
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val portId1 = addFirstBinding(vtepId1, networkId, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe Some(portId1)
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("Deleting the first VTEP")
            store.delete(classOf[Vtep], vtepId1)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("Deleting the second VTEP")
            store.delete(classOf[Vtep], vtepId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None
        }

        scenario("Deleting only VTEPs bound to different networks") {
            Given("Two VTEPs with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val (networkId2, vni2) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId1, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe Some(portId1)
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            When("Deleting the first VTEP")
            store.delete(classOf[Vtep], vtepId1)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))

            When("Deleting the second VTEP")
            store.delete(classOf[Vtep], vtepId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None
        }

        scenario("Deleting VTEPs and bindings to the same network") {
            Given("Two VTEPs with a network binding")
            val (networkId, vni) = addNetwork()
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val portId1 = addFirstBinding(vtepId1, networkId, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe Some(portId1)
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("Deleting the first binding")
            removeLastBinding(vtepId1, networkId, portId1, "port1")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("Deleting the first VTEP")
            store.delete(classOf[Vtep], vtepId1)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("Deleting the second binding")
            removeLastBinding(vtepId2, networkId, portId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None

            When("Deleting the second VTEP")
            store.delete(classOf[Vtep], vtepId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None
        }

        scenario("Deleting VTEPs and bindings to different networks") {
            Given("Two VTEPs with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val (networkId2, vni2) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId1, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe Some(portId1)
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni1))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))

            When("Deleting the first binding")
            removeLastBinding(vtepId1, networkId1, portId1, "port1")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))

            When("Deleting the first VTEP")
            store.delete(classOf[Vtep], vtepId1)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))

            When("Deleting the second binding")
            removeLastBinding(vtepId2, networkId2, portId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None

            When("Deleting the second VTEP")
            store.delete(classOf[Vtep], vtepId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None
        }

        scenario("VTEP removed when emitting an error") {
            Given("A VTEP with a network binding")
            val (vtepId, tunnelIp, tzoneId) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId = addFirstBinding(vtepId, networkId, "port0")

            When("The VTEP is updated with a corrupted tunnel IP")
            corruptTunnelIp(vtepId)

            Then("The mapper removes the VTEP")
            portOf(tunnelIp, vni) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId) shouldBe None
        }

        scenario("VTEP error does not prevent other VTEP addition to the " +
                 "same network") {
            Given("A VTEP with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (networkId, vni) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId, "port1")

            When("The VTEP is updated with a corrupted tunnel IP")
            corruptTunnelIp(vtepId1)

            And("Adding a new VTEP to the same network")
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val portId2 = addFirstBinding(vtepId2, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))
        }

        scenario("VTEP error does not prevent other VTEP addition to the " +
                 "another network") {
            Given("A VTEP with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId1, "port1")

            When("The VTEP is updated with a corrupted tunnel IP")
            corruptTunnelIp(vtepId1)

            And("Adding a new VTEP to the same network")
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val (networkId2, vni2) = addNetwork()
            val portId2 = addFirstBinding(vtepId2, networkId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))
        }

        scenario("VTEP error does not prevent other VTEP update bound to the " +
                 "same network") {
            Given("Two VTEPs with a network binding")
            val (networkId, vni) = addNetwork()
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val portId1 = addFirstBinding(vtepId1, networkId, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe Some(portId1)
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("The first VTEP is updated with a corrupted tunnel IP")
            corruptTunnelIp(vtepId1)

            And("The second VTEP updates the tunnel IP")
            val tunnelIp3 = IPv4Addr.random
            setTunnelIps(vtepId2, tunnelIp3)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe None
            portOf(tunnelIp3, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp3, tzoneId2, vni))
        }

        scenario("VTEP error does not prevent other VTEP update bound to a " +
                 "different network") {
            Given("Two VTEPs with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val (networkId2, vni2) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId1, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe Some(portId1)
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni1))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))

            When("The first VTEP is updated with a corrupted tunnel IP")
            corruptTunnelIp(vtepId1)

            And("The second VTEP updates the tunnel IP")
            val tunnelIp3 = IPv4Addr.random
            setTunnelIps(vtepId2, tunnelIp3)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe None
            portOf(tunnelIp3, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp3, tzoneId2, vni2))
        }

        scenario("VTEP error does not prevent other VTEP removal bound to " +
                 "the same network") {
            Given("Two VTEPs with a network binding")
            val (networkId, vni) = addNetwork()
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val portId1 = addFirstBinding(vtepId1, networkId, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe Some(portId1)
            portOf(tunnelIp2, vni) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni))

            When("The first VTEP is updated with a corrupted tunnel IP")
            corruptTunnelIp(vtepId1)

            And("Removing the second VTEP")
            store.delete(classOf[Vtep], vtepId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni) shouldBe None
            portOf(tunnelIp2, vni) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None
        }

        scenario("VTEP error does not prevent other VTEP removal bound to " +
                 "a different network") {
            Given("Two VTEPs with a network binding")
            val (vtepId1, tunnelIp1, tzoneId1) = addVtep()
            val (vtepId2, tunnelIp2, tzoneId2) = addVtep()
            val (networkId1, vni1) = addNetwork()
            val (networkId2, vni2) = addNetwork()
            val portId1 = addFirstBinding(vtepId1, networkId1, "port1")
            val portId2 = addFirstBinding(vtepId2, networkId2, "port2")

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe Some(portId1)
            portOf(tunnelIp2, vni2) shouldBe Some(portId2)

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe Some(TunnelInfo(tunnelIp1, tzoneId1, vni1))
            tunnelOf(portId2) shouldBe Some(TunnelInfo(tunnelIp2, tzoneId2, vni2))

            When("The first VTEP is updated with a corrupted tunnel IP")
            corruptTunnelIp(vtepId1)

            And("Removing the second VTEP")
            store.delete(classOf[Vtep], vtepId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            portOf(tunnelIp1, vni1) shouldBe None
            portOf(tunnelIp2, vni2) shouldBe None

            And("The mapper returns the VTEP information for the VTEP")
            tunnelOf(portId1) shouldBe None
            tunnelOf(portId2) shouldBe None
        }
    }
}
