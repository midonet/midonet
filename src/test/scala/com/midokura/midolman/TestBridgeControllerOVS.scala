/**
 * TestBridgeControllerOVS.scala - Test BridgeController's interactions with
 *                                 Open vSwitch.
 *
 * Copyright 2011 Midokura Inc.  All rights reserved.
 */

package com.midokura.midolman

import com.midokura.midolman.eventloop.SelectLoop
import com.midokura.midolman.openvswitch.{BridgeBuilder, 
                                          OpenvSwitchDatabaseConnectionImpl}
import com.midokura.midolman.state.{MacPortMap, MockDirectory, 
                                    PortToIntNwAddrMap}

import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.UUID

/**
 * Test the BridgeController's interaction with Open vSwitch.
 */
object TestBridgeControllerOVS extends JUnitSuite {
    // All the "static" variables and methods.
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    private final val bridgeName = "testbr"
    private final val bridgeExtIdKey = "midolman-vnet"
    private final val bridgeExtIdValue = "efbf1194-9e25-11e0-b3b3-ba417460eb69"
    private final val bridgeOfPortNum = 65534
    private final val ovsdb =
        new OpenvSwitchDatabaseConnectionImpl(database, host, port)
    private final var bridgeId: Long = _
    private final val publicIP = /* 192.168.1.50 */
        InetAddress.getByAddress(
            Array(192.toByte, 168.toByte, 1.toByte, 50.toByte))
    private final var controller: BridgeController = _
    private var zkDir = new MockDirectory()
    private final val zkRoot = "/zk_root"

    @BeforeClass def initializeTest() {
        testAddBridge()
        bridgeId = java.lang.Long.parseLong(ovsdb.getDatapathId(bridgeName), 16)

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

    /**
     * Test addBridge().
     */
    def testAddBridge() = {
        val bb: BridgeBuilder = ovsdb.addBridge(bridgeName)
        bb.externalId(bridgeExtIdKey, bridgeExtIdValue)
        bb.build
        assertTrue(ovsdb.hasBridge(bridgeName))
    }

    /**
     * Test delBridge().
     */
    def testDelBridge() = {
        ovsdb.delBridge(bridgeName)
        assertFalse(ovsdb.hasBridge(bridgeName))
    }

    /**
     * Disconnect the OVSDB connection.
     */
    @AfterClass def finalizeTest() = {
        testDelBridge()
        assertFalse(ovsdb.hasBridge(bridgeName))
        ovsdb.close
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

class TestBridgeControllerOVS extends JUnitSuite {
    // import all the statics.
    import TestBridgeControllerOVS._

    @Test def testAddSystemPort() = {
        val portName = "testport"
        addSystemPort(portName)
        assertTrue(ovsdb.hasPort(portName))
        // TODO: Verify this is a system port.
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    @Test def testAddInternalPort() = {
        val portName = "testport"
        addInternalPort(portName)
        assertTrue(ovsdb.hasPort(portName))
        // TODO: Verify this is an internal port.
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    @Test def testAddTapPort() = {
        val portName = "testport"
        addTapPort(portName)
        assertTrue(ovsdb.hasPort(portName))
        // TODO: Verify this is a TAP port.
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

}
