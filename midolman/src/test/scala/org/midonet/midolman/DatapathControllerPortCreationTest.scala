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

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.services.HostIdProvider
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.state.FlowStateStorageFactory
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockUpcallDatapathConnectionManager}
import org.midonet.midolman.util.{MidolmanSpec, MockNetlinkChannelFactory}
import org.midonet.odp.ports.VxLanTunnelPort
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class DatapathControllerPortCreationTest extends MidolmanSpec {
    var testableDpc: DatapathController = _

    val ip = IPv4Addr("1.1.1.1")
    var clusterBridge: UUID = null
    var connManager: MockUpcallDatapathConnectionManager = null
    var interfaceScanner: MockInterfaceScanner = null

    registerActors(
        DatapathController -> (() => new DatapathController(
            new DatapathStateDriver(mockDpConn().futures.datapathsCreate("midonet").get()),
            injector.getInstance(classOf[HostIdProvider]),
            injector.getInstance(classOf[InterfaceScanner]),
            injector.getInstance(classOf[MidolmanConfig]),
            injector.getInstance(classOf[UpcallDatapathConnectionManager]),
            simBackChannel,
            clock,
            injector.getInstance(classOf[FlowStateStorageFactory]),
            new MockNetlinkChannelFactory)))

    override def beforeTest() {
        testableDpc = DatapathController.as[DatapathController]
        testableDpc should not be null

        connManager =
            injector.getInstance(classOf[UpcallDatapathConnectionManager]).
                asInstanceOf[MockUpcallDatapathConnectionManager]
        connManager should not be null

        interfaceScanner = injector.getInstance(classOf[InterfaceScanner]).
            asInstanceOf[MockInterfaceScanner]
        interfaceScanner should not be null
    }


    private def addAndMaterializeBridgePort(hostId: UUID, br: UUID,
            ifname: String): UUID = {
        val port = newBridgePort(br, host = Some(hostId))
        port should not be null
        addHostVrnPortMapping(hostId, port, ifname)
        port
    }

    private def buildTopology() {
        val zone = greTunnelZone("twilight-zone")

        addTunnelZoneMember(zone, hostId, ip)

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null

        fetchDevice[Bridge](clusterBridge)
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

            Then("MTU should be the ddefault MTU (1500) minus overhead (50)")
            DatapathController.minMtu shouldBe config.dhcpMtu
        }

        scenario("One tunnel interface") {
            Given("Two interface (one in a tunnel zone, not the other)")
            addTunnelZoneMember(greTunnelZone("zone"), hostId, IPv4Addr("1.1.1.3"))
            addInterface("if1", 2000, IPv4Addr("1.1.1.2"))
            addInterface("if2", 3000, IPv4Addr("1.1.1.3"))

            Then("The min MTU should be the tunnel interface MTU minus overhead")
            DatapathController.minMtu shouldBe 2950

            When("Removing the tunnel interface")
            interfaceScanner.removeInterface("if2")
            Then("The min MTU should be the default")
            DatapathController.minMtu shouldBe config.dhcpMtu
        }

        scenario("Creating a non-tunnel interface does not update tunnel MTU") {
            Given("Two interfaces (one in a tunnel zone, not the other)")
            addTunnelZoneMember(greTunnelZone("zone"), hostId, IPv4Addr("1.1.1.3"))
            addInterface("if1", 2000, IPv4Addr("1.1.1.2"))
            addInterface("if2", 3000, IPv4Addr("1.1.1.3"))

            Then("The min MTU should be the tunnel interface MTU minus overhead")
            DatapathController.minMtu shouldBe 2950

            When("Removing the non-tunnel interface")
            interfaceScanner.removeInterface("if1")
            Then("The min MTU should not change")
            DatapathController.minMtu shouldBe 2950
        }

        scenario("Two tunnel interfaces") {
            Given("Three interfaces (two in a tunnel zone, not the other)")
            addTunnelZoneMember(greTunnelZone("zone"), hostId, IPv4Addr("1.1.1.3"))
            addTunnelZoneMember(greTunnelZone("zone"), hostId, IPv4Addr("1.1.1.4"))
            addInterface("if1", 2000, IPv4Addr("1.1.1.2"))
            addInterface("if2", 3000, IPv4Addr("1.1.1.3"))
            addInterface("if3", 4000, IPv4Addr("1.1.1.4"))

            Then("The min MTU should be the minimum tunnel interface MTU - overhead")
            DatapathController.minMtu shouldBe 2950

            When("Removing one interface")
            interfaceScanner.removeInterface("if2")
            Then("The min MTU should be updated")
            DatapathController.minMtu shouldBe 3950
        }
    }

    feature("DatapathController manages ports") {
        scenario("Ports are created and removed based on interface status") {
            Given("A default topology")
            buildTopology()

            And("A port status observable")
            val obs = new TestAwaitableObserver[LocalPortActive]
            VirtualToPhysicalMapper.portsActive.subscribe(obs)

            When("a port binding exists")
            val port = addAndMaterializeBridgePort(hostId, clusterBridge, "if1")

            And("its network interface becomes active with an MTU lower than default")
            addInterface("if1", 1000, ip)

            Then("the DpC should create the datapath port")
            testableDpc.driver.getDpPortNumberForVport(port) should not equal null

            And("the min MTU should be the tunnel default one")
            DatapathController.minMtu should be (1000 - VxLanTunnelPort.TUNNEL_OVERHEAD)
            obs.getOnNextEvents.get(0) shouldBe LocalPortActive(port, active = true)

            When("the network interface disappears")
            interfaceScanner.removeInterface("if1")

            Then("the DpC should delete the datapath port")
            testableDpc.driver.getDpPortNumberForVport(port) should be (null)

            And("the min MTU should be the default one")
            DatapathController.minMtu should be (DatapathController.defaultMtu)
            obs.getOnNextEvents.get(1) shouldBe LocalPortActive(port, active = false)

            When("Adding a network interface with an MTU higher than default")
            addInterface("if2", 2000, ip)

            Then("the min MTU should be updated")
            DatapathController.minMtu should be (2000 - VxLanTunnelPort.TUNNEL_OVERHEAD)
        }

        scenario("Ports are created and removed based on bindings") {
            Given("A default topology")
            buildTopology()

            And("A port status observable")
            val obs = new TestAwaitableObserver[LocalPortActive]
            VirtualToPhysicalMapper.portsActive.subscribe(obs)

            When("a network interface exists")
            addInterface("if1", 1000, ip)

            When("and a port binding is created")
            val port = addAndMaterializeBridgePort(hostId, clusterBridge, "if1")

            Then("the DpC should create the datapath port")
            testableDpc.driver.getDpPortNumberForVport(port) should not equal None
            obs.getOnNextEvents.get(0) shouldBe LocalPortActive(port, active = true)

            When("the binding disappears")
            deletePort(port, hostId)

            Then("the DpC should delete the datapath port")
            obs.getOnNextEvents.get(1) shouldBe LocalPortActive(port, active = false)
        }
    }
}
