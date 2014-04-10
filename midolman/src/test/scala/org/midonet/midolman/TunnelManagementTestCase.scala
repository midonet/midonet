/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID

import akka.testkit.TestProbe

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.zones.{GreTunnelZone, GreTunnelZoneHost}
import org.midonet.midolman.DatapathController.DpPortCreate
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.rcu.{Host => RCUHost}
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.odp.ports.{NetDevPort, GreTunnelPort}
import org.midonet.packets.{IPv4Addr, IntIPv4}

@RunWith(classOf[JUnitRunner])
class TunnelManagementTestCase extends MidolmanTestCase
    with Matchers {

    val myselfId = UUID.randomUUID()
    var greZone: GreTunnelZone = null
    var myGreConfig: GreTunnelZoneHost = null
    var herGreConfig: GreTunnelZoneHost = null
    var host1: Host = null
    var host2: Host = null
    var bridge: Bridge = null
    var portOnHost1: BridgePort = null

    var portActiveProbe: TestProbe = null

    override protected def fillConfig(config: HierarchicalConfiguration): HierarchicalConfiguration = {
        config.setProperty("host-host_uuid", myselfId.toString)
        config
    }

    override def beforeTest() {
        greZone = greTunnelZone("default")

        host1 = newHost("me", hostId())
        host2 = newHost("she")

        bridge = newBridge("bridge")

        portOnHost1 = newBridgePort(bridge)

        materializePort(portOnHost1, host1, "port1")

        // Wait to add myself to the tunnel zone so that the tunnel port
        // gets created after the virtual port.
        myGreConfig = new GreTunnelZoneHost(host1.getId)
            .setIp(IPv4Addr.fromString("192.168.100.1").toIntIPv4)

        herGreConfig = new GreTunnelZoneHost(host2.getId)
            .setIp(IPv4Addr.fromString("192.168.200.1").toIntIPv4)

        clusterDataClient()
            .tunnelZonesAddMembership(greZone.getId, herGreConfig)

        portActiveProbe = newProbe()

        // listen to the DatapathController.DpPortCreate
        actors().eventStream.subscribe(
            portActiveProbe.ref, classOf[LocalPortActive])

        // start initialization
        initializeDatapath() should not be (null)
    }

    /**
     * Mostly here to ensure that deletions of tunnel zones result also in
     * deletion of the memberships.
     */
    def testTunnelZoneCreationDeletion() {
        // The zone is created by now, and there is 1 member
        clusterDataClient().tunnelZonesDelete(greZone.getId)
        clusterDataClient().hostsGet(host1.getId).getTunnelZones should have size (0)
        clusterDataClient().tunnelZonesGetMemberships(greZone.getId) should have size (0)
    }

    def testTunnelZone() {

        val port = datapathEventsProbe.expectMsgClass(classOf[DpPortCreate]).port
        port shouldBe a [NetDevPort]
        port.getName shouldBe "port1"

        portActiveProbe.expectMsgClass(classOf[LocalPortActive])

        // check that no route exists yet
        val noRoute = dpState().peerTunnelInfo(host2.getId)
        noRoute should be (None)

        // Now add myself to the tunnel zone.
        clusterDataClient()
            .tunnelZonesAddMembership(greZone.getId, myGreConfig)

        // assert that the VTP got a HostRequest message
        requestOfType[HostRequest](vtpProbe())
        replyOfType[RCUHost](vtpProbe())

        val tzRequest = fishForRequestOfType[TunnelZoneRequest](vtpProbe())
        // assert that the VTP got a TunnelZoneRequest message for the proper zone
        tzRequest.zoneId should be (greZone.getId)

        fishForReplyOfType[GreZoneMembers](vtpProbe())
        fishForReplyOfType[GreZoneChanged](vtpProbe())

        // check that the route was correctly indexed by the DPC
        val route = dpState().peerTunnelInfo(host2.getId)
        route should not be (None)

        // and that the ips match
        val (ipMe, ipShe) = route.getOrElse((0,0))
        ipMe should be (myGreConfig.getIp.addressAsInt())
        ipShe should be (herGreConfig.getIp.addressAsInt())

        // update the gre ip of the second host
        val herSecondGreConfig = new GreTunnelZoneHost(host2.getId)
            .setIp(IPv4Addr.fromString("192.168.210.1").toIntIPv4)
        clusterDataClient().tunnelZonesDeleteMembership(
            greZone.getId, host2.getId)
        clusterDataClient().tunnelZonesAddMembership(
            greZone.getId, herSecondGreConfig)

        Thread.sleep(500) // guard against spurious failures

        // assert new route with updated dst ip
        val route2 = dpState().peerTunnelInfo(host2.getId)
        route2 should not be (None)

        val (ipMe2, ipShe2) = route2.getOrElse((0,0))
        ipMe2 should be (ipMe)
        ipShe2 should not be (ipShe)
        ipShe2 should be (herSecondGreConfig.getIp.addressAsInt)

        // assert datapath state
        val dp = dpConn().futures.datapathsGet("midonet").get()
        dp should not be (null)

        val ports = datapathPorts(dp)
        ports should have size 3
        ports should contain key ("midonet")
        ports should contain key ("port1")
        ports should contain key ("tngre-mm")

        // delete this host from the tunnel zone
        clusterDataClient().tunnelZonesDeleteMembership(greZone.getId, host1.getId)
        fishForReplyOfType[GreZoneChanged](vtpProbe())

        Thread.sleep(500) // guard against spurious failures

        // assert no route left
        val route3 = dpState().peerTunnelInfo(host2.getId)
        route3 should be (None)
    }
}
