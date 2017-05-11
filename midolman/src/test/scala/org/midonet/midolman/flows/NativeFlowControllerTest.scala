/*
 * Copyright (c) 2017, Midokura SARL.
 */
package org.midonet.midolman.flows

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class NativeFlowControllerTest extends FeatureSpec
    with BeforeAndAfterAll with Matchers {

    override def beforeAll() {
        System.loadLibrary("nativeFlowController")
    }

    feature("Test native FlowController") {
        scenario("Basic test") {
            val controller = new NativeFlowController
            controller.test() shouldBe 0
        }
    }
}
