/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import com.midokura.midonet.cluster.data.{Ports, Bridge => ClusterBridge}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midolman.DatapathController.DatapathPortChangedEvent
import topology.VirtualToPhysicalMapper.LocalPortActive
import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class PortSetManagementTestCase extends MidolmanTestCase with ShouldMatchers {

    def testPortSetRegistrationDeregistration() {

        // make a bridge
        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        // make a port on the bridge
        val inputPort = Ports.materializedBridgePort(bridge)
        inputPort.setId(clusterDataClient().portsCreate(inputPort))

        // make a host for myself and put in the proper tunnel zone.
        val myHost = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(myHost.getId, myHost)

        initializeDatapath() should not be null

        // make the bridge port to a local interface
        clusterDataClient().hostsAddVrnPortMapping(hostId, inputPort.getId, "port1")

        // make a probe and make it listen to the DatapathPortChangedEvents (fired by the Datapath Controller)
        val eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[DatapathPortChangedEvent])

        eventProbe.expectMsgClass(classOf[DatapathPortChangedEvent])
        actors().eventStream.unsubscribe(eventProbe.ref)

        actors().eventStream.subscribe(eventProbe.ref, classOf[LocalPortActive])
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        var set = clusterDataClient().portSetsGet(bridge.getId).toSet
        set should contain (hostId())
        set should have size (1)

        clusterDataClient().hostsDelVrnPortMapping(hostId(), inputPort.getId)
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        set = clusterDataClient().portSetsGet(bridge.getId).toSet
        set should have size (0)
    }

    def testMultiplePortSetRegistrationDeregistration() {

        // make a bridge
        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        // make a port on the bridge
        val inputPort1 = Ports.materializedBridgePort(bridge)
        inputPort1.setId(clusterDataClient().portsCreate(inputPort1))

        // make a port on the bridge
        val inputPort2 = Ports.materializedBridgePort(bridge)
        inputPort2.setId(clusterDataClient().portsCreate(inputPort2))

        // make a host for myself and put in the proper tunnel zone.
        val myHost = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(myHost.getId, myHost)

        initializeDatapath() should not be null

        // make the bridge port to a local interface (this should cause the local host to register as a member of the bridge port set).
        clusterDataClient().hostsAddVrnPortMapping(hostId, inputPort1.getId, "port1")
        clusterDataClient().hostsAddVrnPortMapping(hostId, inputPort2.getId, "port2")

        // make a probe to listen to events
        val eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[LocalPortActive])

        // we should see two LocalPortActive events on the bus (fired by the
        // VTPMapper)
        eventProbe.expectMsgClass(classOf[LocalPortActive])
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        // check the portset contents
        var portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should contain (hostId())
        portSet should have size (1)

        // remove a port
        clusterDataClient().hostsDelVrnPortMapping(hostId(), inputPort1.getId)

        // block until the remove event was processed
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        // check the port set contents
        portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should contain (hostId())
        portSet should have size (1)

        // remove the last port
        clusterDataClient().hostsDelVrnPortMapping(hostId(), inputPort2.getId)
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        // check the port set contents
        portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should have size (0)
    }
}
