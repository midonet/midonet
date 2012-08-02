/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.scalatest.matchers.ShouldMatchers
import org.apache.commons.configuration.HierarchicalConfiguration
import akka.actor.ActorRef
import com.midokura.sdn.dp.Datapath
import collection.mutable


class DatapathControllerTestCase extends MidolmanTestCase with ShouldMatchers {

    import scala.collection.JavaConversions._
    import DatapathController._

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("[midolman].midolman_root_key", "/test/v3/midolman2")
        config
    }

    def testDatapathSync() {
        val dpController: ActorRef = topActor(DatapathController.Name)

        // send initialization message and wait
        val reply = sendReply[InitializationComplete](dpController, Initialize())
        reply should not be (null)

        // validate the final datapath state
        val datapaths: mutable.Set[Datapath] =
            datapathConnection().datapathsEnumerate().get()

        datapaths should have size 1
        datapaths.head should have ('name ("new_test") )

        val ports = datapathPorts(datapaths.head)
        ports should have size 3
        ports should contain key ("new_test")
        ports should contain key ("xx")
        ports should contain key ("xy")
    }

}
