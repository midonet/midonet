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
package org.midonet.midolman

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Bridge => ClusterBridge, TunnelZone}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.DatapathController.Initialize
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.topology.{LocalPortActive, VirtualToPhysicalMapper, VirtualTopologyActor}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.midolman.util.mock.MockInterfaceScanner
import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager
import org.midonet.odp.Datapath
import org.midonet.odp.ports.VxLanTunnelPort
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class DatapathControllerPortCreationTest extends MidolmanSpec {
    var datapath: Datapath = null
    var testableDpc: DatapathController = _

    val ip = IPv4Addr("1.1.1.1")
    var tz: TunnelZone = null
    var host: Host = null
    var clusterBridge: ClusterBridge = null
    var connManager: MockUpcallDatapathConnectionManager = null
    var interfaceScanner: MockInterfaceScanner = null

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor),
        VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper
                                          with MessageAccumulator),
        DatapathController -> (() => new DatapathController))

    override def beforeTest() {
        datapath = mockDpConn().futures.datapathsCreate("midonet").get()
        testableDpc = DatapathController.as[DatapathController]
        testableDpc should not be null
        buildTopology()
        DatapathController ! Initialize

        connManager =
            injector.getInstance(classOf[UpcallDatapathConnectionManager]).
                asInstanceOf[MockUpcallDatapathConnectionManager]
        connManager should not be null

        interfaceScanner = injector.getInstance(classOf[InterfaceScanner]).
            asInstanceOf[MockInterfaceScanner]
        interfaceScanner should not be null
    }


    private def addAndMaterializeBridgePort(hostId: UUID, br: ClusterBridge,
            ifname: String): BridgePort = {
        val port = newBridgePort(br)
        port should not be null
        clusterDataClient.hostsAddVrnPortMapping(hostId, port.getId, ifname)
        port
    }

    private def buildTopology() {
        tz = greTunnelZone("twilight-zone")

        host = newHost("myself",
            injector.getInstance(classOf[HostIdProviderService]).getHostId,
            Set(tz.getId))
        host should not be null

        addTunnelZoneMember(tz, host, IPv4Addr("1.1.1.1"))

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null

        fetchTopology(clusterBridge)
    }

    private def addInterface(name: String, mtu: Int, ipAddr: IPv4Addr) {
        val intf = new InterfaceDescription(name)
        intf.setInetAddress(ipAddr.toString)
        intf.setMtu(mtu)
        intf.setUp(true)
        intf.setHasLink(true)
        interfaceScanner.addInterface(intf)
    }

    feature("Minimum MTU is consistent") {
        scenario("No tunnel interfaces") {
            Given("A single interface not in a tunnel zone")
            addInterface("if1", 2000, IPv4Addr("1.1.1.2"))

            Then("MTU should be the default MTU (1500) minus overhead (50)")
            DatapathController.minMtu shouldBe config.dhcpMtu
        }

        scenario("One tunnel interface") {
            Given("Two interface (one in a tunnel zone, not the other)")
            addInterface("if1", 3000, IPv4Addr("1.1.1.1"))
            addInterface("if2", 2000, IPv4Addr("1.1.1.2"))

            Then("The min MTU should be the tunnel interface MTU minus overhead")
            DatapathController.minMtu shouldBe 2950

            When("Removing the tunnel interface")
            interfaceScanner.removeInterface("if1")
            Then("The min MTU should be the default")
            DatapathController.minMtu shouldBe config.dhcpMtu
        }

        scenario("Creating a non-tunnel interface does not update tunnel MTU") {
            Given("Two interfaces (one in a tunnel zone, not the other)")
            addInterface("if1", 3000, IPv4Addr("1.1.1.1"))
            addInterface("if2", 2000, IPv4Addr("1.1.1.2"))

            Then("The min MTU should be the tunnel interface MTU minus overhead")
            DatapathController.minMtu shouldBe 2950

            When("Removing the non-tunnel interface")
            interfaceScanner.removeInterface("if2")
            Then("The min MTU should not change")
            DatapathController.minMtu shouldBe 2950
        }

        scenario("Minimum MTU updated after an updated on the tunnel interfaces") {
            Given("Two interfaces, one in a tunnel zone, not the other")
            testableDpc.cachedInterfaces should have size 0
            addInterface("if1", 2000, IPv4Addr("1.1.1.1"))
            addInterface("ifNew", 1700, IPv4Addr("1.1.1.5"))
            testableDpc.cachedInterfaces should have size 2

            Then("The MTU should be the one from the tunnel interface")
            DatapathController.minMtu shouldBe 1950

            When("Adding a new tunnel interface")
            val tzNew = greTunnelZone("newZone")
            addTunnelZoneMember(tzNew, host, IPv4Addr("1.1.1.5"))

            Then("The MTU should be updated to the minimum of the two")
            DatapathController.minMtu shouldBe 1650
        }
    }

    feature("DatapathController manages ports") {
        scenario("Ports are created and removed based on interface status") {
            When("a port binding exists")
            val port = addAndMaterializeBridgePort(host.getId, clusterBridge, "if1")

            And("its network interface becomes active with an MTU lower than default")
            VirtualToPhysicalMapper.getAndClear()
            addInterface("if1", 1000, ip)

            Then("the DpC should create the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should not equal None

            And("the min MTU should be the default one")
            DatapathController.minMtu should be (
                1000 - VxLanTunnelPort.TunnelOverhead)

            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_,_) => true
                case _ => false
            } map {
                _ match {
                    case LocalPortActive(id,active,_) => (id, active)
                }
            } should equal (List((port.getId, true)))

            When("the network interface disappears")
            VirtualToPhysicalMapper.getAndClear()
            interfaceScanner.removeInterface("if1")

            Then("the DpC should delete the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should equal (None)

            And("the min MTU should be the default one")
            DatapathController.minMtu should be (DatapathController.defaultMtu)

            When("Adding a network interface with an MTU higher than default")
            addInterface("if2", 2000, ip)

            Then("the min MTU should be updated")
            DatapathController.minMtu should be (2000 - VxLanTunnelPort.TunnelOverhead)
        }

        scenario("Ports are created and removed based on bindings") {
            When("a network interface exists")
            addInterface("if1", 1000, ip)

            When("and a port binding is created")
            VirtualToPhysicalMapper.getAndClear()
            val port = addAndMaterializeBridgePort(host.getId, clusterBridge, "if1")

            Then("the DpC should create the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should not equal None
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_,_) => true
                case _ => false
            } map {
                _ match {
                    case LocalPortActive(id,active,_) => (id, active)
                }
            } should equal (List((port.getId, true)))

            When("the binding disappears")
            VirtualToPhysicalMapper.getAndClear()
            clusterDataClient.hostsDelVrnPortMapping(host.getId, port.getId)

            Then("the DpC should delete the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should equal (None)
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_,_) => true
                case _ => false
            } map {
                _ match {
                    case LocalPortActive(id,active,_) => (id, active)
                }
            } should equal (List((port.getId, false)))
        }
    }
}
