/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import collection.mutable
import datapath.{FlowActionOutputToVrnPortSet, FlowActionOutputToVrnPort}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import com.midokura.sdn.dp.{Datapath, Ports}
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge,
                                          Ports => ClusterPorts}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midolman.topology.rcu.{Host => RCUHost}
import com.midokura.midolman.topology.VirtualToPhysicalMapper._
import topology.LocalPortActive
import akka.testkit.TestProbe
import com.midokura.packets.MAC
import com.midokura.sdn.dp.flows.{FlowActionOutput, FlowActions}
import util.SimulationHelper

@RunWith(classOf[JUnitRunner])
class DatapathControllerTestCase extends MidolmanTestCase
        with SimulationHelper with ShouldMatchers {

  import scala.collection.JavaConversions._
  import DatapathController._

  private var portEventsProbe: TestProbe = null
  override def beforeTest() {
      portEventsProbe = newProbe()
      actors().eventStream.subscribe(portEventsProbe.ref,
          classOf[LocalPortActive])
  }

  def testDatapathEmptyDefault() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    dpConn().datapathsEnumerate().get() should have size 0

    // send initialization message and wait
    initializeDatapath()

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("midonet"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 1
    ports should contain key ("midonet")
  }

  def testDatapathAddMappingAfter() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    initializeDatapath() should not be null

    // make a bridge
    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port = ClusterPorts.materializedBridgePort(bridge)
    port.setId(clusterDataClient().portsCreate(port))

    materializePort(port, host, "tapDevice")

    val eventProbe = newProbe()
    actors().eventStream.subscribe(eventProbe.ref, classOf[DatapathPortChangedEvent])
    requestOfType[DatapathPortChangedEvent](eventProbe)
    portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("midonet"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 2
    ports should contain key ("midonet")
    ports should contain key ("tapDevice")
  }

  def testDatapathEmpty() {
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")
    dpConn().datapathsEnumerate().get() should have size 0

    // send initialization message and wait
    initializeDatapath() should not be (null)

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("test"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 1
    ports should contain key ("test")
  }

  def testDatapathEmptyOnePort() {
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port = ClusterPorts.materializedBridgePort(bridge)
    port.setId(clusterDataClient().portsCreate(port))

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")
    materializePort(port, host, "port1")

    dpConn().datapathsEnumerate().get() should have size 0

    // send initialization message and wait
    initializeDatapath() should not be (null)
    portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("test"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 2
    ports should contain key ("test")
    ports should contain key ("port1")
  }

  def testDatapathExistingMore() {
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port = ClusterPorts.materializedBridgePort(bridge)
    port.setId(clusterDataClient().portsCreate(port))

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")
    materializePort(port, host, "port1")

    val dp = dpConn().datapathsCreate("test").get()
    dpConn().portsCreate(dp, Ports.newNetDevPort("port2")).get()
    dpConn().portsCreate(dp, Ports.newNetDevPort("port3")).get()

    dpConn().datapathsEnumerate().get() should have size 1
    dpConn().portsEnumerate(dp).get() should have size 3

    // send initialization message and wait
    initializeDatapath() should not be (null)

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()
      portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    datapaths should have size 1
    datapaths.head should have('name("test"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 2
    ports should contain key ("test")
    ports should contain key ("port1")
  }

  def testDatapathBasicOperations() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")

    initializeDatapath() should not be (null)

    var opReply =
      ask[PortNetdevOpReply](
        DatapathController.getRef(actors()),
        CreatePortNetdev(Ports.newNetDevPort("netdev"), None))

    opReply should not be (null)

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("test"))

    var ports = datapathPorts(datapaths.head)
    ports should have size 2
    ports should contain key ("test")
    ports should contain key ("netdev")

    opReply =
      ask[PortNetdevOpReply](
        DatapathController.getRef(actors()),
        DeletePortNetdev(opReply.port, None))
    opReply should not be (null)

    ports = datapathPorts(datapaths.head)
    ports should have size 1
    ports should contain key ("test")
  }

  def testInternalControllerState() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port1 = ClusterPorts.materializedBridgePort(bridge)
    port1.setId(clusterDataClient().portsCreate(port1))

    materializePort(port1, host, "port1")

    initializeDatapath() should not be (null)
    portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    dpController().underlyingActor.vportMgr
        .getDpPortNumberForVport(port1.getId) should equal(Some(1))

    requestOfType[HostRequest](vtpProbe())
    val rcuHost = replyOfType[RCUHost](vtpProbe())

    rcuHost should not be null
    rcuHost.ports should contain key (port1.getId)
    rcuHost.ports should contain value ("port1")

    requestOfType[LocalPortActive](vtpProbe())


    // make a port on the bridge
    val port2 = ClusterPorts.materializedBridgePort(bridge)
    port2.setId(clusterDataClient().portsCreate(port2))

    materializePort(port2, host, "port2")
    replyOfType[RCUHost](vtpProbe())
    requestOfType[LocalPortActive](vtpProbe())

    dpController().underlyingActor.vportMgr
      .getDpPortNumberForVport(port1.getId) should equal(Some(1))
    dpController().underlyingActor.vportMgr
      .getDpPortNumberForVport(port2.getId) should equal(Some(2))

    //-----------------------
    // Send a generated packet to the DatapathController
    var packetsEventsProbe: TestProbe = newProbe()
    actors().eventStream.subscribe(packetsEventsProbe.ref,
        classOf[PacketsExecute])

    val pkt = makeArpRequest(
        0x0a00000a, MAC.fromString("02:cc:aa:ff:ee:01"), 0x0a00000b)
    DatapathController.getRef(actors()) !
      SendPacket(pkt, new FlowActionOutputToVrnPort(port1.getId) :: Nil)

    // First show that the DC correctly emitted the PacketsExecute message.
    val packet = requestOfType[PacketsExecute](packetsEventsProbe).packet
    packet should not be null
    packet.getData should not be null
    packet.getData should be === pkt.serialize()
    packet.getActions should not be null
    packet.getActions.size should equal(1)
    packet.getActions.get(0).asInstanceOf[FlowActionOutput] should
      equal(FlowActions.output(1))

    // Now show that if after translating the output actions (from VRN to
    // datapath actions), if there are no actions, no PacketsExecute is
    // emitted.
    // Create a second bridge with no ports.
    val bridge2 = new ClusterBridge().setName("test1")
    bridge2.setId(clusterDataClient().bridgesCreate(bridge2))
    DatapathController.getRef(actors()) !
      SendPacket(pkt, new FlowActionOutputToVrnPortSet(bridge2.getId) :: Nil)
    packetsEventsProbe.expectNoMsg()
  }
}
