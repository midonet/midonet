/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.scalatest.matchers.ShouldMatchers
import org.apache.commons.configuration.HierarchicalConfiguration
import akka.actor.ActorRef
import com.midokura.sdn.dp.{Ports, Datapath}
import collection.mutable
import java.util.concurrent.TimeUnit

class DatapathControllerTestCase extends MidolmanTestCase with ShouldMatchers {

    import scala.collection.JavaConversions._
    import DatapathController._

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("[midolman].midolman_root_key", "/test/v3/midolman2")
        config
    }

    def testDatapathFromZero() {
        val dpController: ActorRef = topActor(DatapathController.Name)

        dpConn().datapathsEnumerate().get() should have size 0

        // send initialization message and wait
        val reply = sendReply[InitializationComplete](dpController, Initialize())
        reply should not be (null)

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have ('name ("new_test") )

        val ports = datapathPorts(datapaths.head)
        ports should have size 3
        ports should contain key ("new_test")
        ports should contain key ("xx")
        ports should contain key ("xy")
    }

    def testDatapathSync() {
        val dpController: ActorRef = topActor(DatapathController.Name)

        val datapath = dpConn().datapathsCreate("new_test").get()
        dpConn().portsCreate(datapath, Ports.newNetDevPort("a")).get()
        dpConn().portsCreate(datapath, Ports.newInternalPort("t")).get()

        dpConn().datapathsEnumerate().get() should have size 1
        dpConn().portsEnumerate(datapath).get() should have size 3

        // send initialization message and wait
        val reply = sendReply[InitializationComplete](dpController, Initialize())
        reply should not be (null)

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have ('name ("new_test") )

        val ports = datapathPorts(datapaths.head)
        ports should have size 3
        ports should contain key ("new_test")
        ports should contain key ("xx")
        ports should contain key ("xy")
    }
}
