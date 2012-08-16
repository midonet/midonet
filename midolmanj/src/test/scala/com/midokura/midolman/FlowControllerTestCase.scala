/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.scalatest.matchers.ShouldMatchers
import akka.dispatch.Await

class FlowControllerTestCase extends MidolmanTestCase with ShouldMatchers {

    import akka.pattern.ask
    import akka.util.Timeout
    import akka.util.duration._

    def testDatapathEmptyDefault() {

        initializeDatapath()

        val datapathReady =
            probeByName(FlowController.Name)
                .expectMsgType[DatapathController.DatapathReady]

        datapathReady should not be (null)
        datapathReady.datapath should not be (null)
    }

    protected def initializeDatapath() {
        val timeout = Timeout(1 second)

        val futureResult = ask(topActor(DatapathController.Name), DatapathController.Initialize())(timeout)
        val result = Await.result(futureResult, timeout.duration)
                        .asInstanceOf[DatapathController.InitializationComplete]

        result should not be (null)
    }
}
