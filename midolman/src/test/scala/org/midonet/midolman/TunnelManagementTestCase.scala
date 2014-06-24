/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID

import akka.testkit.TestProbe
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{TunnelZone, Bridge}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.DatapathController.DpPortCreate
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.topology.rcu.{Host => RCUHost}
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.odp.ports.{NetDevPort, GreTunnelPort}
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class TunnelManagementTestCase extends MidolmanTestCase
    with Matchers {

    val myselfId = UUID.randomUUID()
    var greZone: TunnelZone = null
    var myGreConfig: TunnelZone.HostConfig = null
    var herGreConfig: TunnelZone.HostConfig = null
    var host1: Host = null
    var host2: Host = null
    var bridge: Bridge = null
    var portOnHost1: BridgePort = null

    var portActiveProbe: TestProbe = null

    val myIp = IPv4Addr("192.168.100.1")
    val herIp = IPv4Addr("192.168.200.1")
    val herIp2 = IPv4Addr("192.168.210.1")

    override protected def fillConfig(config: HierarchicalConfiguration) = {
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
        myGreConfig = new TunnelZone.HostConfig(host1.getId).setIp(myIp)
        herGreConfig = new TunnelZone.HostConfig(host2.getId).setIp(herIp)

        clusterDataClient()
            .tunnelZonesAddMembership(greZone.getId, herGreConfig)

        portActiveProbe = newProbe()

        // listen to the DatapathController.DpPortCreate
        actors().eventStream
                .subscribe(portActiveProbe.ref, classOf[LocalPortActive])

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
        dpState().peerTunnelInfo(host2.getId) shouldBe None

        // Now add myself to the tunnel zone.
        clusterDataClient().tunnelZonesAddMembership(greZone.getId, myGreConfig)

        // assert that the VTP got a HostRequest message
        requestOfType[HostRequest](vtpProbe())
        replyOfType[RCUHost](vtpProbe())

        val tzRequest = fishForRequestOfType[TunnelZoneRequest](vtpProbe())
        // assert that the VTP got a TunnelZoneRequest message for the proper zone
        tzRequest.zoneId should be (greZone.getId)

        fishForReplyOfType[ZoneMembers](vtpProbe())
        fishForReplyOfType[ZoneChanged](vtpProbe())

        // check that the route was correctly indexed by the DPC
        val route = dpState() peerTunnelInfo host2.getId

        // and that the ips match
        route.get.srcIp shouldBe myIp.toInt
        route.get.dstIp shouldBe herIp.toInt

        // update the gre ip of the second host
        val herSecondGreConfig = new TunnelZone.HostConfig(host2.getId).setIp(herIp2)
        clusterDataClient().tunnelZonesDeleteMembership(greZone.getId, host2.getId)
        clusterDataClient().tunnelZonesAddMembership(greZone.getId, herSecondGreConfig)

        Thread.sleep(500) // guard against spurious failures

        // assert new route with updated dst ip
        val route2 = dpState() peerTunnelInfo host2.getId

        route2.get.srcIp shouldBe myIp.toInt
        route2.get.dstIp should not be (herIp.toInt)
        route2.get.dstIp shouldBe herIp2.toInt

        // assert datapath state
        val dp = dpConn().futures.datapathsGet("midonet").get()
        dp should not be (null)

        val ports = datapathPorts(dp)
        ports should have size 5
        ports should contain key ("midonet")
        ports should contain key ("port1")
        ports should contain key ("tngre-overlay")
        ports should contain key ("tnvxlan-overlay")
        ports should contain key ("tnvxlan-vtep")

        // delete this host from the tunnel zone
        clusterDataClient().tunnelZonesDeleteMembership(greZone.getId, host1.getId)
        fishForReplyOfType[ZoneChanged](vtpProbe())

        Thread.sleep(500) // guard against spurious failures

        // assert no route left
        dpState().peerTunnelInfo(host2.getId) shouldBe None
    }
}
