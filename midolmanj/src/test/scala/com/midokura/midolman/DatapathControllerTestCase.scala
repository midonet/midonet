/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import collection.mutable
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
import topology.VirtualTopologyActor.PortRequest
import com.midokura.midonet.cluster.client.ExteriorBridgePort
import org.junit.{Ignore, Test}
import com.midokura.midolman.FlowController.AddWildcardFlow
import akka.util.Duration
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class DatapathControllerTestCase extends MidolmanTestCase with ShouldMatchers {

  import scala.collection.JavaConversions._
  import DatapathController._

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

    clusterDataClient().hostsAddVrnPortMapping(host.getId, port.getId, "tapDevice")

    val eventProbe = newProbe()
    actors().eventStream.subscribe(eventProbe.ref, classOf[DatapathPortChangedEvent])
    requestOfType[DatapathPortChangedEvent](eventProbe)

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
    clusterDataClient().hostsAddVrnPortMapping(hostId, port.getId, "port1")

    dpConn().datapathsEnumerate().get() should have size 0

    // send initialization message and wait
    initializeDatapath() should not be (null)

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
    clusterDataClient().hostsAddVrnPortMapping(hostId, port.getId, "port1")

    val dp = dpConn().datapathsCreate("test").get()
    dpConn().portsCreate(dp, Ports.newNetDevPort("port2")).get()
    dpConn().portsCreate(dp, Ports.newNetDevPort("port3")).get()

    dpConn().datapathsEnumerate().get() should have size 1
    dpConn().portsEnumerate(dp).get() should have size 3

    // send initialization message and wait
    initializeDatapath() should not be (null)

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

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
        topActor(DatapathController.Name),
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
        topActor(DatapathController.Name),
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

    clusterDataClient().hostsAddVrnPortMapping(hostId, port1.getId, "port1")

    initializeDatapath() should not be (null)

    dpController().underlyingActor.vifPorts should contain key(port1.getId)
    dpController().underlyingActor.vifPorts should contain value ("port1")

    requestOfType[HostRequest](vtpProbe())
    val rcuHost = replyOfType[RCUHost](vtpProbe())

    rcuHost should not be null
    rcuHost.ports should contain key (port1.getId)
    rcuHost.ports should contain value ("port1")

    requestOfType[LocalPortActive](vtpProbe())


    // make a port on the bridge
    val port2 = ClusterPorts.materializedBridgePort(bridge)
    port2.setId(clusterDataClient().portsCreate(port2))

    clusterDataClient().hostsAddVrnPortMapping(hostId(), port2.getId, "port2")
    replyOfType[RCUHost](vtpProbe())
    requestOfType[LocalPortActive](vtpProbe())

    dpController().underlyingActor.vifPorts should contain key(port1.getId)
    dpController().underlyingActor.vifPorts should contain key(port2.getId)

    dpController().underlyingActor.vifPorts should contain value ("port2")
    dpController().underlyingActor.vifPorts should contain value ("port1")
  }

  def testDatapathStartingUpdates() {
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    // create & wait.
    dpConn().datapathsCreate("test").get()

    dpController().underlyingActor.portWatcher should be (null)
    // send initialization message and wait
    initializeDatapath() should not be (null)
    dpController().underlyingActor.portWatcher should not be (null)
    dpController().underlyingActor.portWatcher.isCancelled should be (false)
  }
}
