/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.mutable
import scala.concurrent.duration._
import akka.testkit.TestProbe
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.{Ports => ClusterPorts}
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.topology.VirtualTopologyActor.{PortRequest, BridgeRequest}
import org.midonet.midolman.topology.rcu.{Host => RCUHost}
import org.midonet.midolman.topology.{VirtualTopologyActor, LocalPortActive}
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.odp.Datapath
import org.midonet.odp.DpPort
import org.midonet.odp.ports.NetDevPort
import scala.concurrent.Await

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class DatapathControllerTestCase extends MidolmanTestCase
        with Matchers {
    import scala.collection.JavaConversions._
    import DatapathController._

    private var portEventsProbe: TestProbe = null
    override def beforeTest() {
        portEventsProbe = newProbe()
        actors().eventStream.subscribe(portEventsProbe.ref,
            classOf[LocalPortActive])
    }

    def checkGreTunnel() {
        dpState.tunnelGre should not be (None)
        dpState.greOutputAction should not be (None)
    }

    def testDatapathEmptyDefault() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        dpConn().futures.datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        initializeDatapath()

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().futures.datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have('name("midonet"))

        val ports = datapathPorts(datapaths.head)
        ports should have size 2
        ports should contain key ("tngre-mm")
        ports should contain key ("midonet")
        checkGreTunnel()
    }

    def testDatapathEmpty() {
        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")
        dpConn().futures.datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        initializeDatapath() should not be (null)

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().futures.datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have('name("test"))

        val ports = datapathPorts(datapaths.head)
        ports should have size 2
        ports should contain key ("tngre-mm")
        ports should contain key ("test")
        checkGreTunnel()
    }

    def testDatapathEmptyOnePort() {
        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        // make a port on the bridge
        val port = ClusterPorts.bridgePort(bridge)
        port.setId(clusterDataClient().portsCreate(port))

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")
        materializePort(port, host, "port1")

        dpConn().futures.datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        initializeDatapath() should not be (null)
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().futures.datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have('name("test"))

        val ports = datapathPorts(datapaths.head)
        ports should have size 3
        ports should contain key ("tngre-mm")
        ports should contain key ("test")
        ports should contain key ("port1")
        checkGreTunnel()
    }

    def testDatapathExistingMore() {
        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        // make a port on the bridge
        val port = ClusterPorts.bridgePort(bridge)
        port.setId(clusterDataClient().portsCreate(port))

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")
        materializePort(port, host, "port1")

        val dp = dpConn().futures.datapathsCreate("test").get()
        dpConn().futures.portsCreate(dp, new NetDevPort("port2")).get()
        dpConn().futures.portsCreate(dp, new NetDevPort("port3")).get()

        dpConn().futures.datapathsEnumerate().get() should have size 1
        dpConn().futures.portsEnumerate(dp).get() should have size 3

        // send initialization message and wait
        initializeDatapath() should not be (null)

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().futures.datapathsEnumerate().get()
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        datapaths should have size 1
        datapaths.head should have('name("test"))

        val ports = datapathPorts(datapaths.head)
        ports should have size 3
        ports should contain key ("tngre-mm")
        ports should contain key ("test")
        ports should contain key ("port1")
        checkGreTunnel()
    }

    def testDatapathBasicOperations() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")

        initializeDatapath() should not be (null)

        var opReply = askAndAwait[DpPortReply](
            dpController(), DpPortCreateNetdev(new NetDevPort("netdev"), None))

        opReply should not be (null)
        val netdevPort: NetDevPort = opReply.request.port.asInstanceOf[NetDevPort]

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().futures.datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have('name("test"))

        var ports = datapathPorts(datapaths.head)
        ports should have size 3
        ports should contain key ("tngre-mm")
        ports should contain key ("test")
        ports should contain key ("netdev")
        checkGreTunnel()

        val netdevPortWithPorNo = DpPort.fakeFrom(netdevPort, 3).asInstanceOf[NetDevPort]
        val nextRequest = DpPortDeleteNetdev(netdevPortWithPorNo, None)
        opReply = askAndAwait[DpPortReply](dpController(), nextRequest)
        opReply should not be (null)

        ports = datapathPorts(datapaths.head)
        ports should have size 2
        ports should contain key ("tngre-mm")
        ports should contain key ("test")
        checkGreTunnel()
    }

    def testInternalControllerState() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        // make a port on the bridge
        val port1 = ClusterPorts.bridgePort(bridge)
        port1.setId(clusterDataClient().portsCreate(port1))

        materializePort(port1, host, "port1")

        initializeDatapath() should not be (null)
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val ports = datapathPorts(dpConn().futures.datapathsEnumerate().get().head)
        ports should contain key ("port1")
        val port1DpId = ports("port1").getPortNo

        dpState().getDpPortNumberForVport(port1.getId) should be (Some(port1DpId))

        requestOfType[HostRequest](vtpProbe())
        val rcuHost = replyOfType[RCUHost](vtpProbe())

        rcuHost should not be null
        rcuHost.ports should contain key (port1.getId)
        rcuHost.ports should contain value ("port1")

        requestOfType[LocalPortActive](vtpProbe())

        // make a port on the bridge
        val port2 = ClusterPorts.bridgePort(bridge)
        port2.setId(clusterDataClient().portsCreate(port2))

        materializePort(port2, host, "port2")
        replyOfType[RCUHost](vtpProbe())
        requestOfType[LocalPortActive](vtpProbe())

        val newPorts = datapathPorts(dpConn().futures.datapathsEnumerate().get().head)
        newPorts should contain key ("port1")
        newPorts should contain key ("port2")
        val port2DpId = newPorts("port2").getPortNo

        dpState().getDpPortNumberForVport(port1.getId) should equal(Some(port1DpId))
        dpState().getDpPortNumberForVport(port2.getId) should equal(Some(port2DpId))
    }

}
