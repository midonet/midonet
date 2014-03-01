/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._
import org.scalatest.concurrent.Eventually._

import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.io.{MockUpcallDatapathConnectionManager,
                                UpcallDatapathConnectionManager}
import org.midonet.midolman.DatapathController.Internal.CheckForPortUpdates
import org.midonet.midolman.DatapathController.Initialize
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.services.{HostIdProviderService, MessageAccumulator}
import org.midonet.midolman.topology.{LocalPortActive,
                                      VirtualToPhysicalMapper,
                                      VirtualTopologyActor}
import org.midonet.odp.Datapath

@RunWith(classOf[JUnitRunner])
class DatapathControllerPortCreationTestCase extends FeatureSpec
         with Matchers
         with GivenWhenThen
         with BeforeAndAfter
         with MockMidolmanActors
         with MidolmanServices
         with VirtualTopologyHelper
         with VirtualConfigurationBuilders
         with OneInstancePerTest {

    var datapath: Datapath = null
    var testableDpc: DatapathController = _

    val ifname = "eth0"
    var host: Host = null
    var clusterBridge: ClusterBridge = null
    var connManager: MockUpcallDatapathConnectionManager = null
    var interfaceScanner: MockInterfaceScanner = null

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
            with MessageAccumulator),
        VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper
            with MessageAccumulator),
        DatapathController -> (() => new DatapathController
            with MessageAccumulator))

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
        clusterDataClient().hostsAddVrnPortMapping(hostId, port.getId, ifname)
        port
    }

    private def buildTopology() {
        host = newHost("myself",
            injector.getInstance(classOf[HostIdProviderService]).getHostId)
        host should not be null

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null

        fetchTopology(clusterBridge)
    }

    private def addInterface() {

        val intf = new InterfaceDescription(ifname)
        intf.setInetAddress("1.1.1.1")
        intf.setMtu(1500)
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
            DatapathController ! CheckForPortUpdates("midonet")
            eventually {
                testableDpc.dpState.getDpPortNumberForVport(port.getId) should not equal (None)
            }
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port.getId, true)))

            When("the network interface disappears")
            VirtualToPhysicalMapper.getAndClear()
            interfaceScanner.removeInterface(ifname)

            Then("the DpC should delete the datapath port")
            DatapathController ! CheckForPortUpdates("midonet")
            eventually {
                testableDpc.dpState.getDpPortNumberForVport(port.getId) should equal (None)
            }
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
            DatapathController ! CheckForPortUpdates("midonet")
            eventually {
                testableDpc.dpState.getDpPortNumberForVport(port.getId) should not equal (None)
            }
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port.getId, true)))

            When("the binding disappears")
            VirtualToPhysicalMapper.getAndClear()
            clusterDataClient().hostsDelVrnPortMapping(host.getId, port.getId)

            Then("the DpC should delete the datapath port")
            eventually {
                testableDpc.dpState.getDpPortNumberForVport(port.getId) should equal (None)
            }
            VirtualToPhysicalMapper.getAndClear() filter {
                case LocalPortActive(_,_) => true
                case _ => false
            } should equal (List(LocalPortActive(port.getId, false)))
        }
    }
}
