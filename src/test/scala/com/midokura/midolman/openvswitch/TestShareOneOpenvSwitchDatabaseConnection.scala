/**
 * TestShareOneOpenvSwitchDatabaseConnection.scala - Share a single
 *              OVSDB connection between all the tests using OVSDB.
 *
 * Copyright 2011 Midokura Inc.
 *
 * This is needed because currently OpenvSwitchDatabaseConnectionImpl
 * breaks if you try to use it multiply.  The sharing is accomplished by
 * having an junit Suite make the connection, then call all the classes
 * registered with it.  To make a new class for testing a client of OVSDB,
 * have it import TestShareOneOpenvSwitchDatabaseConnection._ and 
 * acquire/release mutex in its @BeforeClass/@AfterClass, and add it
 * to the list of SuiteClasses.
 */
 
package com.midokura.midolman.openvswitch

import org.junit.{AfterClass, BeforeClass}
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.slf4j.LoggerFactory

import com.midokura.midolman.openvswitch._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._

import java.io.{File, RandomAccessFile}
import java.lang.Long.parseLong
import java.nio.channels.FileLock
import java.util.Date
import java.util.concurrent.Semaphore

object TestShareOneOpenvSwitchDatabaseConnection {
    private final val log = LoggerFactory.getLogger(
                        classOf[TestShareOneOpenvSwitchDatabaseConnection])
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    final val bridgeName = "testovsbr"
    final val bridgeExtIdKey = "midolman-vnet"
    final val bridgeExtIdValue = "efbf1194-9e25-11e0-b3b3-ba417460eb69"
    final var bridgeId: Long = _
    final var ovsdb: OpenvSwitchDatabaseConnectionImpl = _
    final val target = "tcp:127.0.0.1:6635"
    private final var lockfile = new File("/tmp/ovsdbconnection.lock")
    private var lock: FileLock = _
    final val mutex = new Semaphore(0)
    final val finishedSemaphore = new Semaphore(0)

    @BeforeClass def connectToOVSDB() {
        lockfile.setReadable(true, false)
        lockfile.setWritable(true, false)
        lock = new RandomAccessFile(lockfile, "rw").getChannel.lock
        Console.err.println("Entering testOVSConn at " + new Date)
        ovsdb = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        val bridgeTable = ovsdb.dumpBridgeTable
        for { row <- bridgeTable
            if row._1.startsWith("test")
        } { log.info("Deleting preexisting test Bridge {} => {}",
                     row._2, row._1)
            ovsdb.delBridgeUUID(row._2, row._3)
        }
        testAddBridge
        bridgeId = parseLong(ovsdb.getDatapathId(bridgeName), 16)
        ovsdb.delTargetOpenflowControllers(target)
        assertFalse(ovsdb.hasController(target))
        mutex.release
    }

    @AfterClass def disconnectFromOVSDB() {
        finishedSemaphore.acquire(1)
        testDelBridge
        assertFalse(ovsdb.hasBridge(bridgeName))
        ovsdb.close
        Console.err.println("Closing testOVSConn at " + new Date)
        lock.release
    }

    /**
     * Test addBridge().
     */
    def testAddBridge() {
        val bb: BridgeBuilder = ovsdb.addBridge(bridgeName)
        bb.externalId(bridgeExtIdKey, bridgeExtIdValue)
        bb.build
        assertTrue(ovsdb.hasBridge(bridgeName))
    }

    /**
     * Test delBridge().
     */
    def testDelBridge() {
        ovsdb.delBridge(bridgeName)
        assertFalse(ovsdb.hasBridge(bridgeName))
    }
}

@RunWith(classOf[Suite])
@Suite.SuiteClasses(Array(classOf[CheckOpenvSwitchDatabaseConnection]))
class TestShareOneOpenvSwitchDatabaseConnection {
    import TestShareOneOpenvSwitchDatabaseConnection._
}
