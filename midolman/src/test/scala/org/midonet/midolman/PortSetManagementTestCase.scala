/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._
import akka.pattern.Patterns
import akka.dispatch.Await
import akka.util.Duration
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import org.midonet.cluster.data.{Ports, Bridge => ClusterBridge}
import org.midonet.cluster.data.host.Host
import org.midonet.midolman.DatapathController.DpPortCreate
import org.midonet.midolman.topology.rcu.PortSet
import topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest

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

        // make two probes and make them listen DatapathController events
        val portChangedProbe = newProbe()
        actors().eventStream.subscribe(portChangedProbe.ref, classOf[DpPortCreate])
        val portActiveProbe = newProbe()
        actors().eventStream.subscribe(portActiveProbe.ref, classOf[LocalPortActive])

        // make the bridge port to a local interface
        materializePort(inputPort, myHost, "port1")

        portChangedProbe.expectMsgClass(classOf[DpPortCreate])

        portActiveProbe.expectMsgClass(classOf[LocalPortActive])

        var set = clusterDataClient().portSetsGet(bridge.getId).toSet
        set should contain (hostId())
        set should have size (1)

        clusterDataClient().hostsDelVrnPortMapping(hostId(), inputPort.getId)
        portActiveProbe.expectMsgClass(classOf[LocalPortActive])

        set = clusterDataClient().portSetsGet(bridge.getId).toSet
        set should have size (0)
    }

    private def fetchRcuPortSet(id: UUID) : PortSet = {
        val psFuture = Patterns.ask(
            virtualToPhysicalMapper(), new PortSetRequest(id, false), 3000)
        Await.result(psFuture.mapTo[PortSet], Duration.parse("3 seconds"))
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

        // make a probe to listen to events
        val eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[LocalPortActive])

        // make the bridge port to a local interface (this should cause the local host to register as a member of the bridge port set).
        materializePort(inputPort1, myHost, "port1")
        materializePort(inputPort2, myHost, "port2")

        // we should see two LocalPortActive events on the bus (fired by the
        // VTPMapper)
        eventProbe.expectMsgClass(classOf[LocalPortActive])
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        // check the portset contents
        var portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should contain (hostId())
        portSet should have size (1)
        fetchRcuPortSet(bridge.getId).localPorts should have size (2)

        // make a third port
        val inputPort3 = Ports.materializedBridgePort(bridge)
        inputPort3.setId(clusterDataClient().portsCreate(inputPort3))
        materializePort(inputPort3, myHost, "port3")

        eventProbe.expectMsgClass(classOf[LocalPortActive])
        portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should contain (hostId())
        portSet should have size (1)
        fetchRcuPortSet(bridge.getId).localPorts should have size (3)

        // remove a port
        clusterDataClient().hostsDelVrnPortMapping(hostId(), inputPort1.getId)

        // block until the remove event was processed
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        // check the port set contents
        portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should contain (hostId())
        portSet should have size (1)
        fetchRcuPortSet(bridge.getId).localPorts should have size (2)

        // remove another port
        clusterDataClient().hostsDelVrnPortMapping(hostId(), inputPort2.getId)
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        // check the port set contents
        portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should have size (1)
        fetchRcuPortSet(bridge.getId).localPorts should have size (1)

        // remove another port
        clusterDataClient().hostsDelVrnPortMapping(hostId(), inputPort3.getId)
        eventProbe.expectMsgClass(classOf[LocalPortActive])

        // check the port set contents
        portSet = clusterDataClient().portSetsGet(bridge.getId).toSet
        portSet should have size (0)
        fetchRcuPortSet(bridge.getId).localPorts should have size (0)
    }
}
