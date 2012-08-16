/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.scalatest.matchers.ShouldMatchers
import scala.Right
import com.midokura.sdn.dp.Packet

class FlowControllerTestCase extends MidolmanTestCase with ShouldMatchers {

    import akka.pattern.ask
    import akka.util.Timeout
    import akka.util.duration._

    def testDatapathEmptyDefault() {
        initializeDatapath()

//        val datapath = dpConn().datapathsGet("midonet").get()

        val flowActor = topActor[FlowController](FlowController.Name)
        val actor:FlowController = flowActor.underlyingActor

        flowActor ! actor.packetIn(new Packet())
    }

    private def initializeDatapath() {
        implicit val timeout = Timeout(1 second)
        val initialization = topActor(DatapathController.Name) ? DatapathController.Initialize()

        val result = initialization.value.get match {
            case Right(DatapathController.InitializationComplete()) ⇒
                true
            case _ =>
                false
        }

        result should be(true)
    }
}
