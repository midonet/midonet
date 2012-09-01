/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import guice.actors.OutgoingMessage
import org.scalatest.matchers.ShouldMatchers
import java.util.UUID
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Ports, Host}
import com.midokura.midonet.cluster.data.zones.{GreAvailabilityZoneHost, GreAvailabilityZone}
import state.Directory
import topology.physical
import topology.VirtualToPhysicalMapper._
import org.slf4j.{LoggerFactory, Logger}
import org.antlr.stringtemplate.language.InterfaceParserTokenTypes
import com.midokura.packets.IntIPv4
import org.apache.commons.configuration.HierarchicalConfiguration

class TunnelManagementTestCase extends MidolmanTestCase with ShouldMatchers {

    private final val log: Logger = LoggerFactory.getLogger(classOf[TunnelManagementTestCase])

    val myselfId = UUID.randomUUID()

    override protected def fillConfig(config: HierarchicalConfiguration):HierarchicalConfiguration = {
        config.setProperty("host-host_uuid", myselfId.toString)
        config
    }

    import scala.collection.JavaConversions._

    def testAvailabilityZones() {

        val defaultGreZone = new GreAvailabilityZone().setName("greDefault")

        clusterDataClient().availabilityZonesCreate(defaultGreZone)

        val myself =
            new Host(hostId())
                .setName("myself")
                .setAvailabilityZones(Set(defaultGreZone.getId))

        val She =
            new Host(UUID.randomUUID())
                .setName("herself")
                .setAvailabilityZones(Set(defaultGreZone.getId))

        val bridge = clusterDataClient()
            .bridgesCreate(new ClusterBridge().setName("test"))

        val inputPort =
            clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))

        clusterDataClient().hostsCreate(myself.getId, myself)
        clusterDataClient().hostsCreate(She.getId, She)
        clusterDataClient().availabilityZonesAddMembership(
            defaultGreZone.getId,
            new GreAvailabilityZoneHost(myself.getId)
                    .setIp(IntIPv4.fromString("192.168.100.1")))

        clusterDataClient().availabilityZonesAddMembership(
            defaultGreZone.getId,
            new GreAvailabilityZoneHost(She.getId)
                    .setIp(IntIPv4.fromString("192.168.200.1")))

        clusterDataClient().hostsAddVrnPortMapping(hostId, inputPort, "port1")

        initializeDatapath() should not be (null)

        vtpProbe().expectMsgClass(classOf[HostRequest])
        vtpProbe().expectMsgClass(classOf[OutgoingMessage]).m.isInstanceOf[physical.Host] should be (true)
        vtpProbe().expectMsgClass(classOf[AvailabilityZoneRequest]).zoneId should be (defaultGreZone.getId)
        vtpProbe().expectMsgClass(classOf[OutgoingMessage]).m.isInstanceOf[GreAvailabilityZone] should be (true)
        vtpProbe().expectMsgClass(classOf[OutgoingMessage]).m.isInstanceOf[GreZoneChanged] should be (true)
        vtpProbe().expectMsgClass(classOf[OutgoingMessage]).m.isInstanceOf[GreZoneChanged] should be (true)

        ask[Messages.Pong](topActor(DatapathController.Name), Messages.Ping(null))

        val x = 10
//
        //        val dpProbe = probeByName(DatapathController.Name)
//        dpProbe.expectMsg(new Initialize)
//        dpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[InitializationComplete] should not be null
//
//        dpController().underlyingActor.vifPorts should contain key (vifPort)
//        dpController().underlyingActor.vifPorts should contain value ("port1")
//
//        val vtpProbe = probeByName(VirtualToPhysicalMapper.Name)
//        vtpProbe.expectMsgClass(classOf[LocalDatapathRequest]) should not be null
//        vtpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[LocalDatapathReply] should not be null
//        vtpProbe.expectMsgClass(classOf[LocalPortsRequest]) should not be null
//        vtpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[LocalPortsReply] should not be null
//
//        val vifPort_2nd = UUID.randomUUID()
//        clusterDataClient().hostsAddVrnPortMapping(hostId(), vifPort_2nd, "port2")
//
//        val reply = vtpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[LocalPortsReply]
//
//        reply should not be null
//        reply.ports should contain key (vifPort)
//        reply.ports should contain key (vifPort_2nd)
//
//        reply.ports should contain value ("port1")
//        reply.ports should contain value ("port2")
//
//        // make sure that all the messages before this are processes by the actor
//        // (even the internal ones). This should act as a memory barrier
//        ask[Messages.Pong](
//            topActor(DatapathController.Name), Messages.Ping(null))
//
//        dpController().underlyingActor.vifPorts should contain key (vifPort_2nd)
//        dpController().underlyingActor.vifPorts should contain value ("port2")
    }
}
