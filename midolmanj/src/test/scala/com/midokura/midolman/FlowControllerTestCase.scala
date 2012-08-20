/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.scalatest.matchers.ShouldMatchers
import akka.dispatch.Await

class FlowControllerTestCase extends MidolmanTestCase with ShouldMatchers {

    def testDatapathEmptyDefault() {

        initializeDatapath() should not be (null)

        val datapathReady =
            probeByName(FlowController.Name)
                .expectMsgType[DatapathController.DatapathReady]

        datapathReady should not be (null)
        datapathReady.datapath should not be (null)
    }
}
