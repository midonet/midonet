/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.scalatest.matchers.ShouldMatchers
import org.apache.commons.configuration.HierarchicalConfiguration
import akka.actor.ActorRef
import com.midokura.sdn.dp.{Ports, Datapath}
import collection.mutable
import java.util.UUID

class DatapathControllerTestCase extends MidolmanTestCase with ShouldMatchers {
    import scala.collection.JavaConversions._
    import DatapathController._

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        super.fillConfig(config)
    }

    def testDatapathEmptyDefault() {
        val dpController: ActorRef = topActor(DatapathController.Name)

        dpConn().datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        val reply = sendReply[InitializationComplete](dpController, Initialize())
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

        midoStore().setLocalVrnDatapath(hostId, "test")
        dpConn().datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        val reply = sendReply[InitializationComplete](dpController, Initialize())
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

        midoStore().setLocalVrnDatapath(hostId, "test")
        midoStore().setLocalVrnPortMapping(hostId, UUID.randomUUID(), "port1")

        dpConn().datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        val reply = sendReply[InitializationComplete](dpController, Initialize())
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

        midoStore().setLocalVrnDatapath(hostId, "test")
        midoStore().setLocalVrnPortMapping(hostId, UUID.randomUUID(), "port1")

        val dp = dpConn().datapathsCreate("test").get()
        dpConn().portsCreate(dp, Ports.newNetDevPort("port2")).get()
        dpConn().portsCreate(dp, Ports.newNetDevPort("port3")).get()

        dpConn().datapathsEnumerate().get() should have size 1
        dpConn().portsEnumerate(dp).get() should have size 3

        // send initialization message and wait
        val reply = sendReply[InitializationComplete](dpController, Initialize())
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
}
