/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import java.util.UUID
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.slf4j.{Logger, LoggerFactory}

import com.midokura.midolman.DatapathController.DatapathPortChangedEvent
import com.midokura.midolman.topology.VirtualToPhysicalMapper._
import com.midokura.midonet.cluster.data.zones.{GreTunnelZone,
                                                GreTunnelZoneHost}
import com.midokura.midolman.topology.rcu.{Host => RCUHost}
import com.midokura.packets.IntIPv4
import com.midokura.sdn.dp.ports.{NetDevPort, GreTunnelPort}
import topology.LocalPortActive

@RunWith(classOf[JUnitRunner])
class TunnelManagementTestCase extends MidolmanTestCase with ShouldMatchers with VirtualConfigurationBuilders {

    private final val log: Logger = LoggerFactory.getLogger(classOf[TunnelManagementTestCase])

    val myselfId = UUID.randomUUID()

    override protected def fillConfig(config: HierarchicalConfiguration): HierarchicalConfiguration = {
        config.setProperty("host-host_uuid", myselfId.toString)
        config
    }


    def testTunnelZone() {

        val greZone = greTunnelZone("default")

        val host1 = newHost("me", hostId())
        val host2 = newHost("she")

        val bridge = newBridge("bridge")

        val portOnHost1 = newExteriorBridgePort(bridge)

        materializePort(portOnHost1, host1, "port1")

        // Wait to add myself to the tunnel zone so that the tunnel port
        // gets created after the virtual port.
        val myGreConfig = new GreTunnelZoneHost(host1.getId)
            .setIp(IntIPv4.fromString("192.168.100.1"))

        val herGreConfig = new GreTunnelZoneHost(host2.getId)
            .setIp(IntIPv4.fromString("192.168.200.1"))

        clusterDataClient()
            .tunnelZonesAddMembership(greZone.getId, herGreConfig)

        // make a probe and make it listen to the DatapathPortChangedEvents (fired by the Datapath Controller)
        val portChangedProbe = newProbe()
        val portActiveProbe = newProbe()
        actors().eventStream.subscribe(portChangedProbe.ref, classOf[DatapathPortChangedEvent])
        actors().eventStream.subscribe(portActiveProbe.ref, classOf[LocalPortActive])

        // start initialization
        initializeDatapath() should not be (null)

        // assert that the port event was fired properly
        var portChangedEvent = portChangedProbe.expectMsgClass(classOf[DatapathPortChangedEvent])
        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.getName should be("port1")
        portChangedEvent.port.isInstanceOf[NetDevPort] should be(true)

        portActiveProbe.expectMsgClass(classOf[LocalPortActive])

        // Now add myself to the tunnel zone.
        clusterDataClient()
            .tunnelZonesAddMembership(greZone.getId, myGreConfig)

        // assert that the VTP got a HostRequest message
        requestOfType[HostRequest](vtpProbe())
        replyOfType[RCUHost](vtpProbe())

        val tzRequest = fishForRequestOfType[TunnelZoneRequest](vtpProbe())
        // assert that the VTP got a TunnelZoneRequest message for the proper zone
        tzRequest.zoneId should be === greZone.getId

        fishForReplyOfType[GreTunnelZone](vtpProbe())
        fishForReplyOfType[GreZoneChanged](vtpProbe())

        // assert that the creation event for the tunnel was fired.
        portChangedEvent = requestOfType[DatapathPortChangedEvent](portChangedProbe)
        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.getName should be("tngreC0A8C801")
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        var grePort = portChangedEvent.port.asInstanceOf[GreTunnelPort]
        grePort.getOptions.getSourceIPv4 should be(myGreConfig.getIp.addressAsInt())
        grePort.getOptions.getDestinationIPv4 should be(herGreConfig.getIp.addressAsInt())

        // check the internal data in the datapath controller is correct
        // the host peer contains a map which maps the zone to the tunnel name
        dpController().underlyingActor.peerPorts should contain key (host2.getId)
        dpController().underlyingActor.peerPorts should contain value (
            scala.collection.mutable.Map(greZone.getId -> "tngreC0A8C801")
        )

        // update the gre ip of the second host
        val herSecondGreConfig = new GreTunnelZoneHost(host2.getId)
            .setIp(IntIPv4.fromString("192.168.210.1"))
        clusterDataClient().tunnelZonesAddMembership(
            greZone.getId, herSecondGreConfig)

        // assert a delete event was fired on the bus.
        portChangedEvent = requestOfType[DatapathPortChangedEvent](portChangedProbe)
        portChangedEvent.op should be(PortOperation.Delete)
        portChangedEvent.port.getName should be("tngreC0A8C801")
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        // assert the proper datapath port changed event is fired
        portChangedEvent = requestOfType[DatapathPortChangedEvent](portChangedProbe)

        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.getName should be("tngreC0A8D201")
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        grePort = portChangedEvent.port.asInstanceOf[GreTunnelPort]

        grePort.getOptions.getSourceIPv4 should be(myGreConfig.getIp.addressAsInt())
        grePort.getOptions.getDestinationIPv4 should be(herSecondGreConfig.getIp.addressAsInt())

        // assert the internal state of the datapath controller vas fired
        dpController().underlyingActor.peerPorts should contain key (host2.getId)
        dpController().underlyingActor.peerPorts should contain value (
            scala.collection.mutable.Map(greZone.getId -> "tngreC0A8D201")
        )

        val dp = dpConn().datapathsGet("midonet").get()
        dp should not be (null)

        val ports = datapathPorts(dp)
        ports should have size 3
        ports should contain key ("midonet")
        ports should contain key ("port1")
        ports should contain key ("tngreC0A8D201")
    }
}
