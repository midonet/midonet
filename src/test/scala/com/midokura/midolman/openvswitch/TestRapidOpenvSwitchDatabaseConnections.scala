/**
 * TestRapidOpenvSwitchDatabaseConnections.scala - Test rapid sequential 
 *                      OpenvSwitchDatabaseConnectionImpl connections.
 *
 * Copyright 2011 Midokura Inc.  All rights reserved.
 */

package com.midokura.midolman.openvswitch

import com.midokura.midolman.openvswitch._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._

import org.junit.{AfterClass, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import java.io.{File, RandomAccessFile}
import java.lang.Long
import java.nio.channels.FileLock

object TestRapidOpenvSwitchDatabaseConnections extends JUnitSuite {
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    private final val bridgeName = "testrapidbr"
    private final val portName = "testrapidport"
    private final val bridgeExtIdKey = "midolman-vnet"
    private final val bridgeExtIdValue = "f5451278-fddd-8b9c-d658-b167aa6c00cc"
    private final val lockfile = new File("/tmp/ovs_tests.lock")
    private final val lockchannel =
        new RandomAccessFile(lockfile, "rw").getChannel
    private var lock: FileLock = _

    @BeforeClass def initializeTest() {
        lock = lockchannel.lock
        Console.err.println("Entering testRapidOVSConns")
    }

    @AfterClass def finalizeTest() {
        lock.release
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

    @Test @Ignore def testRapidConnections() {
        var conn1 = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        testAddBridge(conn1)
        var bridgeId = Long.parseLong(conn1.getDatapathId(bridgeName), 16)
        testDelBridge(conn1)
        assertFalse(conn1.hasBridge(bridgeName))
        conn1.close
        var conn2 = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        testAddBridge(conn2)
        bridgeId = Long.parseLong(conn2.getDatapathId(bridgeName), 16)
        testDelBridge(conn2)
        assertFalse(conn2.hasBridge(bridgeName))
        conn2.close
    }
}
