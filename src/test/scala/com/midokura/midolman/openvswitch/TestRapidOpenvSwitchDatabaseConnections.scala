/**
 * TestRapidOpenvSwitchDatabaseConnections.scala - Test rapid sequential 
 *                      OpenvSwitchDatabaseConnectionImpl connections.
 *
 * Copyright 2011 Midokura Inc.  All rights reserved.
 */

package com.midokura.midolman.openvswitch

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._

import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.junit.Assume._
import org.scalatest.junit.JUnitSuite

import java.lang.Long.parseLong
import java.net.ConnectException


object TestRapidOpenvSwitchDatabaseConnections 
        extends OpenvSwitchDatabaseConnectionMutexer with JUnitSuite {
    import OpenvSwitchDatabaseConnectionMutexer._

    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    private final val bridgeName = "testrapidbr"
    private final val portName = "testrapidport"
    private final val bridgeExtIdKey = "midolman-vnet"
    private final val bridgeExtIdValue = "f5451278-fddd-8b9c-d658-b167aa6c00cc"

    @BeforeClass def initializeTest() {
        takeLock
    }

    @AfterClass def finalizeTest() {
        releaseLock
    }

    def testAddBridge(conn: OpenvSwitchDatabaseConnection) {
        val bb = conn.addBridge(bridgeName)
        bb.externalId(bridgeExtIdKey, bridgeExtIdValue)
        bb.build
        assertTrue(conn.hasBridge(bridgeName))
    }

    def testDelBridge(conn: OpenvSwitchDatabaseConnection) {
        conn.delBridge(bridgeName)
        assertFalse(conn.hasBridge(bridgeName))
    }
}

class TestRapidOpenvSwitchDatabaseConnections extends JUnitSuite {
    import TestRapidOpenvSwitchDatabaseConnections._

    @Test def testRapidConnections() {
        var conn1:OpenvSwitchDatabaseConnectionImpl = null
        try {
            conn1 = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        } catch {
            case e: ConnectException =>
                assumeNoException(e)
        }
        testAddBridge(conn1)
        var bridgeId = parseLong(conn1.getDatapathId(bridgeName), 16)
        testDelBridge(conn1)
        assertFalse(conn1.hasBridge(bridgeName))
        conn1.close
        var conn2 = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        testAddBridge(conn2)
        bridgeId = parseLong(conn2.getDatapathId(bridgeName), 16)
        testDelBridge(conn2)
        assertFalse(conn2.hasBridge(bridgeName))
        conn2.close
    }
}
