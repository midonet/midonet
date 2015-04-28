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
import org.midonet.midolman.util.{MockNetlinkChannelFactory, MidolmanSpec}
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

    val ifname = "eth0"
    val ifmtu = 1000
    val ip = IPv4Addr("1.1.1.1")
    var host: Host = null
    var clusterBridge: ClusterBridge = null
    var connManager: MockUpcallDatapathConnectionManager = null
    var interfaceScanner: MockInterfaceScanner = null

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor),
        VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper
                                          with MessageAccumulator),
        DatapathController -> (() => new DatapathController(new MockNetlinkChannelFactory)))

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
        val zone = greTunnelZone("twilight-zone")

        host = newHost("myself",
            injector.getInstance(classOf[HostIdProviderService]).hostId,
            Set(zone.getId))
        host should not be null

        clusterDataClient.tunnelZonesAddMembership(zone.getId,
            new TunnelZone.HostConfig(host.getId).setIp(ip))

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null

        fetchTopology(clusterBridge)
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
            val port = addAndMaterializeBridgePort(host.getId, clusterBridge, ifname)

            And("its network interface becomes active")
            VirtualToPhysicalMapper.getAndClear()
            addInterface()

            Then("the DpC should create the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should not equal null

            And("the min MTU should be the default one")
            DatapathController.minMtu should be (
                DatapathController.defaultMtu - VxLanTunnelPort.TunnelOverhead)

            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port.getId, true)))

            When("the network interface disappears")
            VirtualToPhysicalMapper.getAndClear()
            interfaceScanner.removeInterface(ifname)

            Then("the DpC should delete the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should be (null)

            And("the min MTU should be the default one")
            DatapathController.minMtu should be (DatapathController.defaultMtu)

            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port.getId, false)))
        }

        scenario("Ports are created and removed based on bindings") {
            When("a network interface exists")
            addInterface()

            When("and a port binding is created")
            VirtualToPhysicalMapper.getAndClear()
            val port = addAndMaterializeBridgePort(host.getId, clusterBridge, ifname)

            Then("the DpC should create the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should not equal None
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port.getId, true)))

            When("the binding disappears")
            VirtualToPhysicalMapper.getAndClear()
            clusterDataClient.hostsDelVrnPortMapping(host.getId, port.getId)

            Then("the DpC should delete the datapath port")
            testableDpc.dpState.getDpPortNumberForVport(port.getId) should be (null)
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port.getId, false)))
        }
    }
}
