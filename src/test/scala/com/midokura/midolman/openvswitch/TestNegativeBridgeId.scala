// Copyright 2011 Midokura Inc.

package com.midokura.midolman.openvswitch

import org.junit.{AfterClass, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.junit.Assume.assumeNoException
import org.scalatest.junit.JUnitSuite
import org.slf4j.LoggerFactory


import java.lang.Long.parseLong
import java.net.ConnectException

import OpenvSwitchDatabaseConnectionImpl._

object TestNegativeBridgeId
        extends OpenvSwitchDatabaseConnectionMutexer with JUnitSuite {
    import OpenvSwitchDatabaseConnectionMutexer._

    private final val log = 
                    LoggerFactory.getLogger(classOf[TestNegativeBridgeId])
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    private final val bridgeName = "testnegbr"
    private final val bridgeId = 0xa4a027d6e9288adbL
    var conn: OpenvSwitchDatabaseConnectionImpl = null

    @BeforeClass def initializeTest() {
        takeLock
        try {
            conn = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        } catch {
            case e: ConnectException => assumeNoException(e)
        }
        testAddBridge(conn)
    }

    @AfterClass def finalizeTest() {
        try {
            if (conn != null)
                conn.close
        } finally {
            releaseLock
        }
    }

    def testAddBridge(conn: OpenvSwitchDatabaseConnection) {
        val bb = conn.addBridge(bridgeName)
        bb.datapathId(bridgeId)
        bb.build
        assertTrue(conn.hasBridge(bridgeName))
    }
}

class TestNegativeBridgeId extends JUnitSuite {
    import TestNegativeBridgeId._

    @Test def testNegativeBridgeId() {
        val datapathId = conn.getDatapathId(bridgeName)
        log.info("bridgeId = {} :: datapathId = {}",
                 bridgeId formatted "%x", datapathId)
        assertTrue(bridgeId == datapathIdToLong(datapathId))
        assertEquals(longToDatapathId(bridgeId), datapathId)
    }
}
