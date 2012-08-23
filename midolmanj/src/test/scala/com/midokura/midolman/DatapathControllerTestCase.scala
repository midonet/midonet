/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import guice.actors.OutgoingMessage
import org.scalatest.matchers.ShouldMatchers
import akka.actor.ActorRef
import com.midokura.sdn.dp.{Ports, Datapath}
import collection.mutable
import java.util.UUID
import topology.VirtualToPhysicalMapper
import topology.VirtualToPhysicalMapper.{LocalPortsReply, LocalPortsRequest, LocalDatapathRequest, LocalDatapathReply}

class DatapathControllerTestCase extends MidolmanTestCase with ShouldMatchers {

    import scala.collection.JavaConversions._
    import DatapathController._

    def testDatapathEmptyDefault() {
        val dpController: ActorRef = topActor(DatapathController.Name)

        dpConn().datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        val reply = ask[InitializationComplete](dpController, Initialize())
        reply should not be (null)

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have('name("midonet"))

        val ports = datapathPorts(datapaths.head)
        ports should have size 1
        ports should contain key ("midonet")
    }

    def testDatapathEmpty() {
        val dpController: ActorRef = topActor(DatapathController.Name)

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")
        dpConn().datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        val reply = ask[InitializationComplete](dpController, Initialize())
        reply should not be (null)

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have('name("test"))

        val ports = datapathPorts(datapaths.head)
        ports should have size 1
        ports should contain key ("test")
    }

    def testDatapathEmptyOnePort() {
        val dpController: ActorRef = topActor(DatapathController.Name)

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")
        clusterDataClient().hostsAddVrnPortMapping(hostId, UUID.randomUUID(), "port1")

        dpConn().datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        val reply = ask[InitializationComplete](dpController, Initialize())
        reply should not be (null)

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
        val dpController: ActorRef = topActor(DatapathController.Name)

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")
        clusterDataClient().hostsAddVrnPortMapping(hostId, UUID.randomUUID(), "port1")

        val dp = dpConn().datapathsCreate("test").get()
        dpConn().portsCreate(dp, Ports.newNetDevPort("port2")).get()
        dpConn().portsCreate(dp, Ports.newNetDevPort("port3")).get()

        dpConn().datapathsEnumerate().get() should have size 1
        dpConn().portsEnumerate(dp).get() should have size 3

        // send initialization message and wait
        val reply = ask[InitializationComplete](dpController, Initialize())
        reply should not be (null)

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

        clusterDataClient().hostsAddDatapathMapping(hostId, "test")

        initializeDatapath() should not be (null)

        var opReply =
            ask[PortNetdevOpReply](
                topActor(DatapathController.Name),
                CreatePortNetdev(Ports.newNetDevPort("netdev")))

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
                DeletePortNetdev(opReply.port))
        opReply should not be (null)

        ports = datapathPorts(datapaths.head)
        ports should have size 1
        ports should contain key ("test")
    }

    def testInternalControllerState() {
        val vifPort = UUID.randomUUID()
        clusterDataClient().hostsAddVrnPortMapping(hostId, vifPort, "port1")

        initializeDatapath() should not be (null)

        val dpProbe = probeByName(DatapathController.Name)
        dpProbe.expectMsg(new Initialize)
        dpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[InitializationComplete] should not be null

        dpController().underlyingActor.vifPorts should contain key(vifPort)
        dpController().underlyingActor.vifPorts should contain value ("port1")

        val vtpProbe = probeByName(VirtualToPhysicalMapper.Name)
        vtpProbe.expectMsgClass(classOf[LocalDatapathRequest]) should not be null
        vtpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[LocalDatapathReply] should not be null
        vtpProbe.expectMsgClass(classOf[LocalPortsRequest]) should not be null
        vtpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[LocalPortsReply] should not be null

        val vifPort_2nd = UUID.randomUUID()
        clusterDataClient().hostsAddVrnPortMapping(hostId(), vifPort_2nd, "port2")

        val reply = vtpProbe.expectMsgClass(classOf[OutgoingMessage]).m.asInstanceOf[LocalPortsReply]

        reply should not be null
        reply.ports should contain key (vifPort)
        reply.ports should contain key (vifPort_2nd)

        reply.ports should contain value ("port1")
        reply.ports should contain value ("port2")

        // make sure that all the messages before this are processes by the actor
        // (even the internal ones). This should act as a memory barrier
        ask[Messages.Pong](
            topActor(DatapathController.Name), Messages.Ping(null))

        dpController().underlyingActor.vifPorts should contain key(vifPort_2nd)
        dpController().underlyingActor.vifPorts should contain value ("port2")
    }
}
