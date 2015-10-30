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
import org.midonet.midolman.topology.{LocalPortActive, VirtualToPhysicalMapper}
import org.midonet.midolman.util.mock.{MessageAccumulator, MockInterfaceScanner, MockUpcallDatapathConnectionManager}
import org.midonet.midolman.util.{MidolmanSpec, MockNetlinkChannelFactory}
import org.midonet.odp.ports.VxLanTunnelPort
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class DatapathControllerPortCreationTest extends MidolmanSpec {
    var testableDpc: DatapathController = _

    val ifname = "eth0"
    val ifmtu = 1000
    val ip = IPv4Addr("1.1.1.1")
    var clusterBridge: UUID = null
    var connManager: MockUpcallDatapathConnectionManager = null
    var interfaceScanner: MockInterfaceScanner = null

    registerActors(
        VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper
                                          with MessageAccumulator),
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
        buildTopology()

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

    private def addInterface() {
        ifmtu should not be DatapathController.defaultMtu
        val intf = new InterfaceDescription(ifname)
        intf.setInetAddress(ip.toString)
        intf.setMtu(ifmtu)
        intf.setUp(true)
        intf.setHasLink(true)
        interfaceScanner.addInterface(intf)
    }

    feature("DatapathController manages ports") {
        scenario("Ports are created and removed based on interface status") {
            When("a port binding exists")
            val port = addAndMaterializeBridgePort(hostId, clusterBridge, ifname)

            And("its network interface becomes active")
            VirtualToPhysicalMapper.getAndClear()
            addInterface()

            Then("the DpC should create the datapath port")
            testableDpc.driver.getDpPortNumberForVport(port) should not equal null

            And("the min MTU should be the default one")
            DatapathController.minMtu should be (
                DatapathController.defaultMtu - VxLanTunnelPort.TunnelOverhead)

            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port, true)))

            When("the network interface disappears")
            VirtualToPhysicalMapper.getAndClear()
            interfaceScanner.removeInterface(ifname)

            Then("the DpC should delete the datapath port")
            testableDpc.driver.getDpPortNumberForVport(port) should be (null)

            And("the min MTU should be the default one")
            DatapathController.minMtu should be (DatapathController.defaultMtu)

            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port, false)))
        }

        scenario("Ports are created and removed based on bindings") {
            When("a network interface exists")
            addInterface()

            When("and a port binding is created")
            VirtualToPhysicalMapper.getAndClear()
            val port = addAndMaterializeBridgePort(hostId, clusterBridge, ifname)

            Then("the DpC should create the datapath port")
            testableDpc.driver.getDpPortNumberForVport(port) should not equal None
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port, true)))

            When("the binding disappears")
            VirtualToPhysicalMapper.getAndClear()
            deletePort(port, hostId)

            Then("the DpC should delete the datapath port")
            testableDpc.driver.getDpPortNumberForVport(port) should be (null)
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port, false)))
        }
    }
}
