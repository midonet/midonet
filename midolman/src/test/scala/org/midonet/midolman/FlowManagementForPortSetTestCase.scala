/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.immutable
import scala.util.control.Breaks._

import akka.util.Duration
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.zones.{GreTunnelZone, GreTunnelZoneHost}
import org.midonet.cluster.data.{Port, Bridge}
import org.midonet.midolman.DeduplicationActor.HandlePackets
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.{FlowTagger, LocalPortActive}
import org.midonet.odp.FlowMatch
import org.midonet.odp.Packet
import org.midonet.odp.flows.FlowKeys.{ethernet, inPort, tunnel}
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class FlowManagementForPortSetTestCase extends MidolmanTestCase
        with VirtualConfigurationBuilders {

    var tunnelZone: GreTunnelZone = null

    var host1: Host = null
    var host2: Host = null
    var host3: Host = null

    val ip1 = IPv4Addr("192.168.100.1")
    val ip2 = IPv4Addr("192.168.125.1")
    val ip3 = IPv4Addr("192.168.150.1")

    var bridge: Bridge = null
    var tunnelId = 0

    private val log = LoggerFactory.getLogger(
        classOf[FlowManagementForPortSetTestCase])

    override def beforeTest() {
        tunnelZone = greTunnelZone("default")

        host1 = newHost("host1", hostId())
        host2 = newHost("host2")
        host3 = newHost("host3")

        bridge = newBridge("bridge")

        for ( (host, ip) <- List(host1,host2,host3).zip(List(ip1,ip2,ip3)) ) {
            val zoneId = tunnelZone.getId
            val greHost = new GreTunnelZoneHost(host.getId).setIp(ip.toIntIPv4)
            clusterDataClient().tunnelZonesAddMembership(zoneId, greHost)
        }

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        tunnelId = dpState.tunnelGre.get.getPortNo
    }

    def testInstallFlowForPortSet() {

        val port1OnHost1 = newBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val port2OnHost1 = newBridgePort(bridge)
        //port2OnHost1.getTunnelKey should be (3)
        val port3OnHost1 = newBridgePort(bridge)
        //port3OnHost1.getTunnelKey should be (4)
        val portOnHost2 = newBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (5)
        val portOnHost3 = newBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (6)

        val srcMAC = MAC.fromString("00:11:22:33:44:55")
        val chain1 = newOutboundChainOnPort("chain1", port2OnHost1)
        val condition = new Condition
        condition.dlSrc = srcMAC
        val rule1 = newLiteralRuleOnChain(chain1, 1, condition,
                RuleResult.Action.DROP)

        materializePort(port1OnHost1, host1, "port1a")
        materializePort(port2OnHost1, host1, "port1b")
        materializePort(port3OnHost1, host1, "port1c")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")

        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val localPortNumber1 = getPortNumber("port1a")
        val localPortNumber2 = getPortNumber("port1b")
        val localPortNumber3 = getPortNumber("port1c")

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(port1OnHost1.getId)
                                          .setEthernetSource(srcMAC),
            actions = List(new FlowActionOutputToVrnPortSet(bridge.getId)))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        addFlowMsg should not be null
        //addFlowMsg.f should equal (wildcardFlow)
        addFlowMsg.f.getMatch.getInputPortUUID should be(null)
        addFlowMsg.f.getMatch.getInputPortNumber should be(localPortNumber1)

        val flowActs = addFlowMsg.f.getActions.toList

        flowActs should not be (null)

        val tunKey = bridge.getTunnelKey
        val (outputs, tunInf) = parseTunnelActions(flowActs)

        outputs should have size(3)
        outputs.contains(
            FlowActions.output(tunnelId)) should be (true)
        outputs.contains(
            FlowActions.output(localPortNumber3)) should be (true)
        outputs.contains(
            FlowActions.output(localPortNumber2)) should not be (true)

        tunInf should have size(2)
        tunInf.find(tunnelIsLike(
            ip1.addr, ip2.addr, tunKey)) should not be None
        tunInf.find(tunnelIsLike(
            ip1.addr, ip3.addr, tunKey)) should not be None

    }

    def testInstallFlowForPortSetFromTunnel() {
        log.debug("Starting testInstallFlowForPortSetFromTunnel")

        val port1OnHost1 = newBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val port2OnHost1 = newBridgePort(bridge)
        //port2OnHost1.getTunnelKey should be (3)
        val port3OnHost1 = newBridgePort(bridge)
        //port3OnHost1.getTunnelKey should be (4)
        val portOnHost2 = newBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (5)
        val portOnHost3 = newBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (6)

        val srcMAC = MAC.fromString("00:11:22:33:44:55")
        val dstMAC = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val chain1 = newOutboundChainOnPort("chain1", port2OnHost1)
        val condition = new Condition
        condition.dlSrc = srcMAC
        val rule1 = newLiteralRuleOnChain(chain1, 1, condition,
                RuleResult.Action.DROP)

        materializePort(port1OnHost1, host1, "port1a")
        materializePort(port2OnHost1, host1, "port1b")
        materializePort(port3OnHost1, host1, "port1c")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val localPortNumber1 = getPortNumber("port1a")
        val localPortNumber2 = getPortNumber("port1b")
        val localPortNumber3 = getPortNumber("port1c")

        val dpMatch = new FlowMatch()
                .addKey(ethernet(srcMAC.getAddress, dstMAC.getAddress))
                .addKey(inPort(tunnelId))
                .addKey(tunnel(bridge.getTunnelKey, 42, 142))

        val eth = new Ethernet().
                setSourceMACAddress(srcMAC).
                setDestinationMACAddress(dstMAC).
                setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setPayload(new Data("Payload".getBytes)))

        val packet = new Packet().setMatch(dpMatch).setPacket(eth)
        dedupProbe().testActor.tell(HandlePackets(Array(packet)))

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        addFlowMsg should not be null
        addFlowMsg.f.getMatch.getInputPortUUID should be (null)
        addFlowMsg.f.getMatch.getInputPortNumber should be (tunnelId)

        val flowActs = addFlowMsg.f.getActions.toList

        // Should output to local ports 1 and 3 only.
        flowActs should not be (null)
        flowActs should have size (2)
        flowActs.contains(
            FlowActions.output(localPortNumber1)) should be (true)
        flowActs.contains(
            FlowActions.output(localPortNumber2)) should not be (true)
        flowActs.contains(
            FlowActions.output(localPortNumber3)) should be (true)
    }

    def testInvalidationHostRemovedFromPortSet() {

        val port1OnHost1 = newBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val portOnHost2 = newBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (3)
        val portOnHost3 = newBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (4)


        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")

        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        requestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(port1OnHost1.getId),
            actions = List(new FlowActionOutputToVrnPortSet(bridge.getId)))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val flowToInvalidate = requestOfType[WildcardFlowAdded](wflowAddedProbe)
        // delete one host from the portSet
        clusterDataClient().portSetsDelHost(bridge.getId, host3.getId)
        // expect flow invalidation for the flow tagged using the bridge id and portSet id,
        // which are the same

        val wanted = FlowTagger.invalidateBroadcastFlows(bridge.getId, bridge.getId)
        breakable { while (true) {
            val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
            if (msg.tag.equals(wanted))
                break()
        } }

        val flowInvalidated = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.f))
    }

    def testInvalidationNewHostAddedToPortSet() {

        val port1OnHost1 = newBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val portOnHost2 = newBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (3)
        val portOnHost3 = newBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (4)


        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(port1OnHost1.getId),
            actions = List(new FlowActionOutputToVrnPortSet(bridge.getId)))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val flowToInvalidate =
            fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // expect flow invalidation for the flow tagged using the bridge id and
        // portSet id, which are the same
        val wanted = FlowTagger.invalidateBroadcastFlows(bridge.getId, bridge.getId)
        breakable { while (true) {
            val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
            if (msg.tag.equals(wanted))
                break()
        } }

        val flowInvalidated = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.f))
    }

    def testInvalidationNewPortAddedToPortSet() {

        val port1OnHost1 = newBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val portOnHost2 = newBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (3)
        val portOnHost3 = newBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (4)


        materializePort(port1OnHost1, host1, "port1a")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")

        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(port1OnHost1.getId),
            actions = List(new FlowActionOutputToVrnPortSet(bridge.getId)))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val flowToInvalidate =
            fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        val port2OnHost1_unMaterialized = newBridgePort(bridge)
        //port2OnHost1.getTunnelKey should be (5)
        // Drain the flowProbe
        while(flowProbe().msgAvailable)
            flowProbe().receiveOne(Duration(10, TimeUnit.MILLISECONDS))
        val port2OnHost1 =
          materializePort(port2OnHost1_unMaterialized, host1, "port1b")
        //port2OnHost1 = getPort()
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        var numPort2OnHost1: Short = 0
        vifToLocalPortNumber(port2OnHost1.getId) match {
            case Some(portNo : Short) => numPort2OnHost1 = portNo
            case None =>
                fail("Short data port number for port2OnHost1 not found.")
        }

        // Expect various invalidation messages, not necessarily in order
        // to prevent races
        var expected = immutable.Set(
            FlowTagger.invalidateDPPort(numPort2OnHost1),
            FlowTagger.invalidateByTunnelKey(port2OnHost1.getTunnelKey),
            FlowTagger.invalidateBroadcastFlows(bridge.getId, bridge.getId)
        )

        for (i <- 1.to(expected size)) {
            val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
            expected = expected.filter(exp => !exp.equals(msg.tag))
        }

        expected should have size (0)

        val flowInvalidated = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.f))
    }

    def testInvalidationRemovePortFromPortSet() {

        val port1OnHost1 = newBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val port2OnHost1 = newBridgePort(bridge)
        //port2OnHost1.getTunnelKey should be (3)
        val portOnHost2 = newBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (4)
        val portOnHost3 = newBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (5)

        materializePort(port1OnHost1, host1, "port1a")
        materializePort(port2OnHost1, host1, "port1b")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")

        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgAllClassOf(classOf[LocalPortActive],
                                       classOf[LocalPortActive])

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(port1OnHost1.getId),
            actions = List(new FlowActionOutputToVrnPortSet(bridge.getId)))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        // delete the port. The dp controller will invalidate all the flow whose
        // source or destination is this port. The port set is expanded into several
        // flows, the one corresponding to this port will be removed
        deletePort(port2OnHost1, host1)
        val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        wflowRemovedProbe.expectMsgAllClassOf(classOf[WildcardFlowRemoved],
                                              classOf[WildcardFlowRemoved])
    }
}
