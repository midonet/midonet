/**
 * TestOpenvSwitchDatabaaseConnection.scala - OVSDB connection Test.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.openvswitch


import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitSuite

import com.midokura.midolman.openvswitch._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._

/**
 * Test for the Open vSwitch database connection.
 */
object TestOpenvSwitchDatabaseConnection extends JUnitSuite {
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    private final val bridgeName = "testbr"
    private final val portName = "testport"
    private final val bridgeExtIdKey = "midolman-vnet"
    private final val bridgeExtIdValue = "efbf1194-9e25-11e0-b3b3-ba417460eb69"
    private final val bridgeOfPortNum = 65534
    private final val ovsdb =
        new OpenvSwitchDatabaseConnectionImpl(database, host, port)
    private final var bridgeId: Long = _

    @BeforeClass def initializeTest() {
        testAddBridge()
        bridgeId = java.lang.Long.parseLong(ovsdb.getDatapathId(bridgeName),
                                            16)
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
}

class TestOpenvSwitchDatabaseConnection extends JUnitSuite {
    import TestOpenvSwitchDatabaseConnection._

    /**
     * Test addSystemPort().
     */
    @Test def testAddSystemPort() = {
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        pb = ovsdb.addSystemPort(bridgeId, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addInternalPort().
     */
    @Test def testAddInternalPort() = {
        var pb = ovsdb.addInternalPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        pb = ovsdb.addInternalPort(bridgeId, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addTapPort().
     */
    @Test def testAddTapPort() = {
        var pb = ovsdb.addTapPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        pb = ovsdb.addTapPort(bridgeId, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addGrePort().
     */
    @Test def testAddGrePort() = {
        var pb = ovsdb.addGrePort(bridgeName, portName, "127.0.0.1")
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        pb = ovsdb.addGrePort(bridgeId, portName, "127.0.0.1")
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addOpenflowController().
     */
    @Test def testAddOpenflowController() = {
        val target = "tcp:127.0.0.1"
        var cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
        cb.build
        assertTrue(ovsdb.hasController(target))
        ovsdb.delBridgeOpenflowControllers(bridgeName)
        assertFalse(ovsdb.hasController(target))

        cb = ovsdb.addBridgeOpenflowController(bridgeId, target)
        cb.build
        assertTrue(ovsdb.hasController(target))
        ovsdb.delBridgeOpenflowControllers(bridgeId)
        assertFalse(ovsdb.hasController(target))
    }

    /**
     * Test getDatapathExternalId().
     */
    @Test def testGetDatapathExternalId() = {
        assertTrue(ovsdb.hasBridge(bridgeName))
        assertEquals(ovsdb.getDatapathExternalId(bridgeName, bridgeExtIdKey),
                     bridgeExtIdValue)
        assertEquals(ovsdb.getDatapathExternalId(bridgeId, bridgeExtIdKey),
                     bridgeExtIdValue)
    }

    /**
     * Test getPortExternalId().
     */
    @Test def testGetPortExternalId() = {
        assertEquals(ovsdb.getPortExternalId(bridgeName, bridgeOfPortNum,
                                             bridgeExtIdKey), "")
        assertEquals(ovsdb.getPortExternalId(bridgeId, bridgeOfPortNum,
                                             bridgeExtIdKey), "")

        val portExtIdKey = bridgeExtIdKey
        val portExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.externalId(portExtIdKey, portExtIdValue)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        assertEquals(ovsdb.getPortExternalId(portName, portExtIdKey),
                     portExtIdValue)
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test getBridgeNamesByExternalId().
     */
    @Test def testGetBridgeNamesByExternalId() = {
        assertTrue(ovsdb.hasBridge(bridgeName))
        assertTrue(ovsdb.getBridgeNamesByExternalId(
            bridgeExtIdKey, bridgeExtIdValue).contains(bridgeName))
    }

    /**
     * Test getPortNamesByExternalId().
     */
    @Test def testGetPortNamesByExternalId() = {
        val portExtIdKey = bridgeExtIdKey
        val portExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.externalId(portExtIdKey, portExtIdValue)
        pb.build
        assertTrue(ovsdb.getPortNamesByExternalId(
            portExtIdKey, portExtIdValue).contains(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addQos and delQos.
     */
    @Test def testAddQos() = {
        val qosType = "linux-htb"
        val qosExtIdKey = bridgeExtIdKey
        val qosExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        val qb = ovsdb.addQos(qosType)
        qb.externalId(qosExtIdKey, qosExtIdValue)
        val uuid = qb.build
        assertTrue(ovsdb.hasQos(uuid))
        ovsdb.delQos(uuid)
        assertFalse(ovsdb.hasQos(uuid))
    }

    /**
     * Test addQueue and delQueue.
     */
    @Test def testAddQueue() = {
        val queueMinRate: Long = 100000000
        val queueExtIdKey = bridgeExtIdKey
        val queueExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        val qb = ovsdb.addQueue()
        qb.minRate(queueMinRate)
        qb.externalId(queueExtIdKey, queueExtIdValue)
        val uuid = qb.build
        assertTrue(ovsdb.hasQueue(uuid))
        ovsdb.delQueue(uuid)
        assertFalse(ovsdb.hasQueue(uuid))
    }

    /**
     * Test setPortQos and unsetPortQos.
     */
    @Test def testSetPortQos() = {
        val qosType = "linux-htb"
        val qosExtIdKey = bridgeExtIdKey
        val qosExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        val qb = ovsdb.addQos(qosType)
        qb.externalId(qosExtIdKey, qosExtIdValue)
        val qosUUID = qb.build
        assertTrue(ovsdb.hasQos(qosUUID))
        ovsdb.setPortQos(portName, qosUUID)
        assertFalse(ovsdb.isEmptyColumn(TablePort, portName, "qos"))
        ovsdb.unsetPortQos(portName)
        assertTrue(ovsdb.isEmptyColumn(TablePort, portName, "qos"))
        ovsdb.delQos(qosUUID)
        assertFalse(ovsdb.hasQos(qosUUID))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }
}
