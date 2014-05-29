/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.test;

import java.nio.ByteBuffer
import java.util.ArrayList

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

//import org.midonet.netlink._
import org.midonet.odp._
import org.midonet.odp.protos._

/*
 * This class reproduces the ovs integration test as a single unit test.
 * Instead of using a real Datapath connection, it uses and exercices the
 * MockOvsDatapathConnection, OvsIntegrationTest, and also serves as a unit
 * tests for OvsConnectionOps.
 */
@RunWith(classOf[JUnitRunner])
class OvsConnectionOpsTest extends Suite with Matchers {

  def testRunIntegrationTestSuite() {
      val mockConTest = new OvsIntegrationTestBase {
          val baseConnection = OvsDatapathConnection.createMock()
      }
      mockConTest.run() shouldBe true
  }

}
