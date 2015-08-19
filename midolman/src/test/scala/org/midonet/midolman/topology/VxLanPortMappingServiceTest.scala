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
import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.Vtep.Binding
import org.midonet.cluster.models.Topology.{Network, Vtep}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VxLanPortMappingService.uuidOf
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
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
        service = new VxLanPortMappingService(vt)
        service.startAsync().awaitRunning()
    }

    protected override def afterTest(): Unit = {
        service.stopAsync().awaitTerminated()
    }

    private def newVtep(vtepId: UUID = UUID.randomUUID,
                        tunnelIps: Seq[IPv4Addr] = Seq(IPv4Addr.random))
    : (UUID, IPv4Addr) = {
        val vtep = createVtep(id = vtepId)
        store create vtep
        setTunnelIps(vtepId, tunnelIps: _*)
        (vtepId, tunnelIps.head)
    }

    private def setTunnelIps(vtepId: UUID, tunnelIps: IPv4Addr*): Unit = {
        val vtepConfig = VtepConfiguration.newBuilder()
            .setVtepId(vtepId.asProto)
            .addAllTunnelAddresses(tunnelIps.map(_.asProto).asJava)
            .build()
        stateStore.setVtepConfig(vtepId, vtepConfig).await(timeout)
    }

    private def newNetwork(networkId: UUID = UUID.randomUUID,
                           vni: Int = random.nextInt(1 << 24))
    : (UUID, Int) = {
        val network = createBridge(id = networkId,
                                   vni = Some(vni))
        store create network
        (networkId, vni)
    }

    private def addPort(networkId: UUID, vtepId: UUID): UUID = {
        val port = createVxLanPort(bridgeId = Some(networkId),
                                   vtepId = Some(vtepId))
        val network = store.get(classOf[Network], networkId).await(timeout)
                           .toBuilder
                           .addVxlanPortIds(port.getId)
                           .build()
        store.multi(Seq(CreateOp(port), UpdateOp(network)))
        port.getId
    }

    private def addBinding(vtepId: UUID, networkId: UUID): Unit = {
        val vtep = store.get(classOf[Vtep], vtepId).await(timeout)
                        .toBuilder
                        .addBindings(Binding.newBuilder()
                                            .setNetworkId(networkId.asProto)
                                            .build())
                        .build()
        store update vtep
    }

    private def removeBinding(vtepId: UUID, networkId: UUID): Unit = {
        val vtep = store.get(classOf[Vtep], vtepId).await(timeout)
        store update vtep.toBuilder
                         .clearBindings()
                         .addAllBindings(vtep.getBindingsList.asScala
                                             .filterNot(_.getNetworkId.asJava ==
                                                        networkId)
                                             .asJava)
                         .build()
    }

    private def setBindings(vtepId: UUID, networkIds: UUID*): Unit = {
        val vtep = store.get(classOf[Vtep], vtepId).await(timeout)
                        .toBuilder
                        .clearBindings()
                        .addAllBindings(networkIds.map(id =>
                            Binding.newBuilder()
                                   .setNetworkId(id.asProto)
                                   .build()
                            ).asJava)
                        .build()
        store update vtep
    }

    feature("Mapper returns None for non-existing keys") {
        scenario("Non-existing VNI") {
            Given("A VTEP")
            newVtep()

            Then("The mapper returns none for a random VNI")
            uuidOf(IPv4Addr.random, random.nextInt()) shouldBe None
        }

        scenario("Non-existing tunnel IP") {
            Given("A VTEP with a network binding")
            val (vtepId, _) = newVtep()
            val (networkId, vni) = newNetwork()
            addPort(networkId, vtepId)
            addBinding(vtepId, networkId)

            Then("The mapper returns none for a random tunnel IP")
            uuidOf(IPv4Addr.random, vni) shouldBe None
        }
    }

    feature("Mapper processes VTEP updates") {
        scenario("A VTEP with a network binding") {
            Given("A VTEP with a network binding")
            val (vtepId, tunnelIp) = newVtep()
            val (networkId, vni) = newNetwork()
            val portId = addPort(networkId, vtepId)
            addBinding(vtepId, networkId)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp, vni) shouldBe Some(portId)
        }

        scenario("Adding bindings") {
            Given("A VTEP with a binding")
            val (vtepId, tunnelIp) = newVtep()
            val (networkId1, vni1) = newNetwork()
            val portId1 = addPort(networkId1, vtepId)
            addBinding(vtepId, networkId1)

            When("Adding a binding to a second network")
            val (networkId2, vni2) = newNetwork()
            val portId2 = addPort(networkId2, vtepId)
            addBinding(vtepId, networkId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp, vni1) shouldBe Some(portId1)
            uuidOf(tunnelIp, vni2) shouldBe Some(portId2)

            When("Adding a binding to a third network")
            val (networkId3, vni3) = newNetwork()
            val portId3 = addPort(networkId3, vtepId)
            addBinding(vtepId, networkId3)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp, vni1) shouldBe Some(portId1)
            uuidOf(tunnelIp, vni2) shouldBe Some(portId2)
            uuidOf(tunnelIp, vni3) shouldBe Some(portId3)
        }

        scenario("Removing binding") {
            Given("A VTEP with two bindings")
            val (vtepId, tunnelIp) = newVtep()
            val (networkId1, vni1) = newNetwork()
            val (networkId2, vni2) = newNetwork()
            val portId1 = addPort(networkId1, vtepId)
            val portId2 = addPort(networkId2, vtepId)
            addBinding(vtepId, networkId1)
            addBinding(vtepId, networkId2)

            When("Removing the first binding")
            removeBinding(vtepId, networkId1)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp, vni1) shouldBe None
            uuidOf(tunnelIp, vni2) shouldBe Some(portId2)

            When("Removing the second binding")
            removeBinding(vtepId, networkId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp, vni1) shouldBe None
            uuidOf(tunnelIp, vni2) shouldBe None
        }

        scenario("Replace bindings") {
            Given("A VTEP with two bindings")
            val (vtepId, tunnelIp) = newVtep()
            val (networkId1, vni1) = newNetwork()
            val (networkId2, vni2) = newNetwork()
            val portId1 = addPort(networkId1, vtepId)
            val portId2 = addPort(networkId2, vtepId)
            addBinding(vtepId, networkId1)
            addBinding(vtepId, networkId2)

            When("Replacing the bindings with other two bindings")
            val (networkId3, vni3) = newNetwork()
            val (networkId4, vni4) = newNetwork()
            val portId3 = addPort(networkId3, vtepId)
            val portId4 = addPort(networkId4, vtepId)
            setBindings(vtepId, networkId3, networkId4)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp, vni1) shouldBe None
            uuidOf(tunnelIp, vni2) shouldBe None
            uuidOf(tunnelIp, vni3) shouldBe Some(portId3)
            uuidOf(tunnelIp, vni4) shouldBe Some(portId4)
        }

        scenario("Updating the VTEP tunnel IP") {
            Given("A VTEP with a network binding")
            val (vtepId, tunnelIp1) = newVtep()
            val (networkId, vni) = newNetwork()
            val portId = addPort(networkId, vtepId)
            addBinding(vtepId, networkId)

            When("Changing the tunnel IP")
            val tunnelIp2 = IPv4Addr.random
            setTunnelIps(vtepId, tunnelIp2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp1, vni) shouldBe None
            uuidOf(tunnelIp2, vni) shouldBe Some(portId)
        }

        scenario("Adding a VTEP with binding to the same network") {
            Given("A VTEP with a network binding")
            val (vtepId1, tunnelIp1) = newVtep()
            val (networkId, vni) = newNetwork()
            val portId1 = addPort(networkId, vtepId1)
            addBinding(vtepId1, networkId)

            When("Adding a second VTEP")
            val (vtepId2, tunnelIp2) = newVtep()
            val portId2 = addPort(networkId, vtepId2)
            addBinding(vtepId2, networkId)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp1, vni) shouldBe Some(portId1)
            uuidOf(tunnelIp2, vni) shouldBe Some(portId2)

            When("Adding a third VTEP")
            val (vtepId3, tunnelIp3) = newVtep()
            val portId3 = addPort(networkId, vtepId3)
            addBinding(vtepId3, networkId)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp1, vni) shouldBe Some(portId1)
            uuidOf(tunnelIp2, vni) shouldBe Some(portId2)
            uuidOf(tunnelIp3, vni) shouldBe Some(portId3)
        }

        scenario("Adding a VTEP with a binding to a different network") {
            Given("A VTEP with a network binding")
            val (vtepId1, tunnelIp1) = newVtep()
            val (networkId1, vni1) = newNetwork()
            val portId1 = addPort(networkId1, vtepId1)
            addBinding(vtepId1, networkId1)

            When("Adding a second VTEP")
            val (vtepId2, tunnelIp2) = newVtep()
            val (networkId2, vni2) = newNetwork()
            val portId2 = addPort(networkId2, vtepId2)
            addBinding(vtepId2, networkId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp1, vni1) shouldBe Some(portId1)
            uuidOf(tunnelIp2, vni2) shouldBe Some(portId2)
            uuidOf(tunnelIp1, vni2) shouldBe None
            uuidOf(tunnelIp2, vni1) shouldBe None
        }
/*
        scenario("Deleting VTEPs bound to the same network") {
            Given("Two VTEPs with a network binding")
            val (networkId, vni) = newNetwork()
            val (vtepId1, tunnelIp1) = newVtep()
            val (vtepId2, tunnelIp2) = newVtep()
            val portId1 = addPort(networkId, vtepId1)
            val portId2 = addPort(networkId, vtepId2)
            addBinding(vtepId1, networkId)
            addBinding(vtepId2, networkId)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp1, vni) shouldBe Some(portId1)
            uuidOf(tunnelIp2, vni) shouldBe Some(portId2)

            When("Deleting the first VTEP")
            store.delete(classOf[Vtep], vtepId1)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp1, vni) shouldBe None
            uuidOf(tunnelIp2, vni) shouldBe Some(portId2)

            When("Deleting the second VTEP")
            store.delete(classOf[Vtep], vtepId2)

            Then("The mapper returns the port for the tunnel IP and VNI")
            uuidOf(tunnelIp1, vni) shouldBe None
            uuidOf(tunnelIp2, vni) shouldBe None
        }*/
    }

    feature("Mapper processes network updates") {
        scenario("A network with missing VXLAN ports") {

        }

    }
}
