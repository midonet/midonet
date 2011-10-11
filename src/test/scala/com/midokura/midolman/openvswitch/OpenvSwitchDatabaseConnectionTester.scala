// Copyright 2011 Midokura Inc.

package com.midokura.midolman.openvswitch

import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.junit.Assume.assumeNoException
import org.slf4j.LoggerFactory

import java.io.{File, RandomAccessFile}
import java.lang.Long.parseLong
import java.net.ConnectException
import java.nio.channels.FileLock
import java.util.Date


// Mutex the OVSDB Connection, using a mode 666 lockfile in /tmp
object OpenvSwitchDatabaseConnectionMutexer {
    private final var lockfile = new File("/tmp/ovsdbconnection.lock")
    private var lock: FileLock = _

    def takeLock() = {
        lockfile.setReadable(true, false)
        lockfile.setWritable(true, false)
        lock = new RandomAccessFile(lockfile, "rw").getChannel.lock
    }

    def releaseLock() { lock.release }
}
class OpenvSwitchDatabaseConnectionMutexer {}


// Connect to OVSDB; delete any pre-existing test bridges, test controllers,
// test interfaces.  And disconnect.
trait OpenvSwitchDatabaseConnectionBridgeConnector 
        extends OpenvSwitchDatabaseConnectionMutexer {
    import OpenvSwitchDatabaseConnectionMutexer._
    private final val log = LoggerFactory.getLogger(
                          classOf[OpenvSwitchDatabaseConnectionBridgeConnector])
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    val bridgeName: String
    val bridgeExtIdKey: String
    val bridgeExtIdValue: String
    val bridgeId: Long
    var ovsdb: OpenvSwitchDatabaseConnectionImpl = _
    final val target = "tcp:127.0.0.1:6635"

    @BeforeClass def connectToOVSDB() {
        takeLock
        try {
            ovsdb = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        } catch {
            case e: ConnectException => assumeNoException(e)
        }
        val bridgeTable = ovsdb.dumpBridgeTable
        for { row <- bridgeTable
            if row._1.startsWith("test")
        } { log.info("Deleting preexisting test Bridge {} => {}",
                     row._2, row._1)
            ovsdb.delBridgeUUID(row._2, row._3)
        }
        testAddBridge
        val datapathId = ovsdb.getDatapathId(bridgeName)
        log.debug("ovsdb has datapathId = {} (expected {})",
                  datapathId, bridgeId formatted "%x")
        assertEquals(bridgeId, parseLong(datapathId, 16))
        log.debug("Deleting controllers for target {}", target)
        ovsdb.delTargetOpenflowControllers(target)
        assertFalse(ovsdb.hasController(target))
        log.info("Successfully connected to OVSDB.")
        val interfaceTable = ovsdb.dumpInterfaceTable
        log.debug("Interface table: {}", interfaceTable)
        for { row <- interfaceTable
            if row._1.contains("test")
        } { log.info("Deleting preexisting test Interface {} => {}",
                     row._2, row._1)
            // A port can have the interface, therefore the port should be
            // deleted first.
            if (ovsdb.hasPort(row._1))
                ovsdb.delPort(row._1)
            ovsdb.delInterface(row._2)
        }
    }

    @AfterClass def disconnectFromOVSDB() {
        try {
            if (null != ovsdb) {
                testDelBridge
                assertFalse(ovsdb.hasBridge(bridgeName))
                ovsdb.close
            }
            log.debug("Closing testOVSConn at {}", new Date)
        } finally {
            releaseLock
        }
    }

    /**
     * Test addBridge().
     */
    def testAddBridge() {
        log.info("Adding bridge {}", bridgeName)
        val bb: BridgeBuilder = ovsdb.addBridge(bridgeName)
        bb.externalId(bridgeExtIdKey, bridgeExtIdValue)
        bb.datapathId(bridgeId)
        bb.build
        assertTrue(ovsdb.hasBridge(bridgeName))
        log.info("Addition of bridge {} successful", bridgeName)
    }

    /**
     * Test delBridge().
     */
    def testDelBridge() {
        ovsdb.delBridge(bridgeName)
        assertFalse(ovsdb.hasBridge(bridgeName))
    }
}
