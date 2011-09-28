/**
 * CheckBridgeControllerOVS.scala - Test BridgeController's interactions with
 *                                  Open vSwitch.
 *
 * Copyright 2011 Midokura Inc.  All rights reserved.
 */

package com.midokura.midolman

import com.midokura.midolman.eventloop.SelectLoop
import com.midokura.midolman.openvswitch.{
		BridgeBuilder, 
		OpenvSwitchDatabaseConnectionImpl, 
		TestShareOneOpenvSwitchDatabaseConnection}
import com.midokura.midolman.state.{MacPortMap, MockDirectory, 
                                    PortToIntNwAddrMap}

import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import java.io.{File, RandomAccessFile}
import java.net.InetAddress
import java.nio.channels.FileLock
import java.util.concurrent.Executors
import java.util.{Date, UUID}

/**
 * Test the BridgeController's interaction with Open vSwitch.
 */
object CheckBridgeControllerOVS {
    // Share a common OVSDB connection because using two breaks.
    import TestShareOneOpenvSwitchDatabaseConnection._

    // All the "static" variables and methods.
    private final val testportName = "testbrport"
    private final val publicIP = /* 192.168.1.50 */
        InetAddress.getByAddress(
            Array(192.toByte, 168.toByte, 1.toByte, 50.toByte))
    private final var controller: BridgeController = _
    private var zkDir = new MockDirectory
    private final val zkRoot = "/zk_root"

    @BeforeClass def initializeTest() {
        // Set up the (mock) ZooKeeper directories.
        val portLocKey = "/port_locs"
        val macPortKey = "/mac_port"
        val noData = Array[Byte]()
        val midoDirName = zkDir.add(zkRoot, noData, CreateMode.PERSISTENT)
        var midoDir = zkDir.getSubDirectory(midoDirName)
        midoDir.add(portLocKey, noData, CreateMode.PERSISTENT)
        midoDir.add(macPortKey, noData, CreateMode.PERSISTENT)
        val portLocMap = new PortToIntNwAddrMap(
            midoDir.getSubDirectory(portLocKey))
        val macPortMap = new MacPortMap(midoDir.getSubDirectory(macPortKey))
        val reactor = new SelectLoop(Executors.newScheduledThreadPool(1))

        controller = new BridgeController(
            /* datapathId */                bridgeId,
            /* switchUuid */                UUID.fromString(bridgeExtIdValue),
            /* greKey */                    0xe1234,
            /* port_loc_map */              portLocMap,
            /* mac_port_map */              macPortMap,
            /* flowExpireMillis */          300*1000,
            /* idleFlowExpireMillis */      60*1000,
            /* publicIp */                  publicIP,
            /* macPortTimeoutMillis */      40*1000,
            /* ovsdb */                     ovsdb,
            /* reactor */                   reactor,
            /* externalIdKey */             bridgeExtIdKey);

        // XXX: Get a connection to the OF switch, triggering onConnectionMade
    }

    def addSystemPort(portName : String) = {
        ovsdb.addSystemPort(bridgeName, portName).build
    }

    def addInternalPort(portName : String) = {
        ovsdb.addInternalPort(bridgeName, portName).build
    }

    def addTapPort(portName : String) = {
        ovsdb.addTapPort(bridgeName, portName).build
    }
}

class CheckBridgeControllerOVS {
    import TestShareOneOpenvSwitchDatabaseConnection._

    // import all the statics.
    import CheckBridgeControllerOVS._

    @Test def testAddSystemPort() = {
        val portName = testportName
        addSystemPort(portName)
        assertTrue(ovsdb.hasPort(portName))
        // TODO: Verify this is a system port.
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    @Test def testAddInternalPort() = {
        val portName = testportName
        addInternalPort(portName)
        assertTrue(ovsdb.hasPort(portName))
        // TODO: Verify this is an internal port.
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    @Test def testAddTapPort() = {
        val portName = testportName
        addTapPort(portName)
        assertTrue(ovsdb.hasPort(portName))
        // TODO: Verify this is a TAP port.
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }
}
