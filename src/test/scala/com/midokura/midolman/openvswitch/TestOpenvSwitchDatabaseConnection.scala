/**
 * TestOpenvSwitchDatabaaseConnection.scala - OVSDB connection Test.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.openvswitch


import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.scalatest.junit.JUnitSuite

import com.midokura.midolman.CheckBridgeControllerOVS
import com.midokura.midolman.openvswitch._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._

import java.io.{File, RandomAccessFile}
import java.lang.Long.parseLong
import java.nio.channels.FileLock
import java.util.Date

object TestShareOneOpenvSwitchDatabaseConnection {
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    final val bridgeName = "testovsbr"
    final val bridgeExtIdKey = "midolman-vnet"
    final val bridgeExtIdValue = "efbf1194-9e25-11e0-b3b3-ba417460eb69"
    final var bridgeId: Long = _
    final var ovsdb: OpenvSwitchDatabaseConnectionImpl = _
    private final val lockfile = new File("/tmp/ovs_tests.lock")
    private final val lockchannel = 
        new RandomAccessFile(lockfile, "rw").getChannel
    private var lock: FileLock = _

    @BeforeClass def connectToOVSDB() {
        lock = lockchannel.lock
        Console.err.println("Entering testOVSConn at " + new Date)
        ovsdb = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        testAddBridge
        bridgeId = parseLong(ovsdb.getDatapathId(bridgeName), 16)
    }

    @AfterClass def disconnectFromOVSDB() {
        testDelBridge()
        assertFalse(ovsdb.hasBridge(bridgeName))
        ovsdb.close
        Console.err.println("Closing testOVSConn at " + new Date)
        lock.release
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
}

@RunWith(classOf[Suite])
@Suite.SuiteClasses(Array(classOf[CheckOpenvSwitchDatabaseConnection],
			  classOf[CheckBridgeControllerOVS]))
class TestShareOneOpenvSwitchDatabaseConnection { 
    import TestShareOneOpenvSwitchDatabaseConnection._
}

/**
 * Test for the Open vSwitch database connection.
 */
object CheckOpenvSwitchDatabaseConnection {
    private final val portName = "testovsport"
    private final val bridgeOfPortNum = 65534
}

class CheckOpenvSwitchDatabaseConnection {
    import CheckOpenvSwitchDatabaseConnection._
    import TestShareOneOpenvSwitchDatabaseConnection._

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
                                             bridgeExtIdKey), null)
        assertEquals(ovsdb.getPortExternalId(bridgeId, bridgeOfPortNum,
                                             bridgeExtIdKey), null)

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
     * Test addQos and delQos.
     */
    @Test def testUpdateQos() = {
        val qosType = "linux-htb"
        val qosExtIdKey = bridgeExtIdKey
        val qosExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        val updatedQosExtIdValue = "002bcb5f-0000-8000-1000-foobarbuzqux"
        var qb = ovsdb.addQos(qosType)
        qb.externalId(qosExtIdKey, qosExtIdValue)
        val uuid = qb.build
        assertTrue(ovsdb.hasQos(uuid))
        assertTrue(!ovsdb.getQosUUIDsByExternalId(
            qosExtIdKey, qosExtIdValue).isEmpty)
        qb = ovsdb.updateQos(uuid,
            externalIds=Some(Map(qosExtIdKey -> updatedQosExtIdValue)))
        qb.update(uuid)
        assertFalse(!ovsdb.getQosUUIDsByExternalId(
            qosExtIdKey, qosExtIdValue).isEmpty)
        assertTrue(!ovsdb.getQosUUIDsByExternalId(
            qosExtIdKey, updatedQosExtIdValue).isEmpty)
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
        assertFalse(ovsdb.isEmptyColumn(TablePort, portName, ColumnQos))
        ovsdb.unsetPortQos(portName)
        assertTrue(ovsdb.isEmptyColumn(TablePort, portName, ColumnQos))
        ovsdb.delQos(qosUUID)
        assertFalse(ovsdb.hasQos(qosUUID))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test updateQueue
     */
    @Test def testUpdateQueue() = {
        val queueMinRate: Long = 100000000
        val queueExtIdKey = bridgeExtIdKey
        val queueExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        val updatedQueueExtIdValue = "002bcb5f-0000-8000-1000-foobarbuzqux"
        var qb = ovsdb.addQueue()
        qb.minRate(queueMinRate)
        qb.externalId(queueExtIdKey, queueExtIdValue)
        val uuid = qb.build
        assertTrue(ovsdb.hasQueue(uuid))
        assertTrue(!ovsdb.getQueueUUIDsByExternalId(
            queueExtIdKey, queueExtIdValue).isEmpty)
        qb = ovsdb.updateQueue(uuid, minRate=Some(queueMinRate/10),
                               externalIds=Some(
                                   Map(queueExtIdKey -> updatedQueueExtIdValue)))
        qb.update(uuid)
        assertFalse(!ovsdb.getQueueUUIDsByExternalId(
            queueExtIdKey, queueExtIdValue).isEmpty)
        assertTrue(!ovsdb.getQueueUUIDsByExternalId(
            queueExtIdKey, updatedQueueExtIdValue).isEmpty)
        ovsdb.delQueue(uuid)
        assertFalse(ovsdb.hasQueue(uuid))
    }

    /**
     * Test cleaQosQueues
     */
    @Test def testClearQosQueeus() = {
        val qosType = "linux-htb"
        var qosBuilder = ovsdb.addQos(qosType)
        val qosUUID = qosBuilder.build
        qosBuilder.clear
        assertTrue(ovsdb.hasQos(qosUUID))
        val queueMinRate: Long = 100000000
        var queueBuilder = ovsdb.addQueue()
        queueBuilder.minRate(queueMinRate)
        val queueUUID = queueBuilder.build
        queueBuilder.clear
        assertTrue(ovsdb.hasQueue(queueUUID))
        qosBuilder = ovsdb.updateQos(
            qosUUID, queueUUIDs = Some(Map((1: Long) -> queueUUID)))
        qosBuilder.update(qosUUID)
        assertFalse(ovsdb.isEmptyColumn(TableQos, qosUUID, ColumnQueues))
        ovsdb.clearQosQueues(qosUUID)
        assertFalse(!ovsdb.isEmptyColumn(TableQos, qosUUID, ColumnQueues))
        ovsdb.delQueue(queueUUID)
        assertFalse(ovsdb.hasQueue(queueUUID))
        ovsdb.delQos(qosUUID)
        assertFalse(ovsdb.hasQos(qosUUID))
    }

    /**
     * Test getQueueUUIDByQueueNum
     */
    @Test def testGetQueueUUIDByQueueNum() = {
        val qosType = "linux-htb"
        var qosBuilder = ovsdb.addQos(qosType)
        val qosUUID = qosBuilder.build
        qosBuilder.clear
        assertTrue(ovsdb.hasQos(qosUUID))
        val queueMinRate: Long = 100000000
        var queueBuilder = ovsdb.addQueue()
        queueBuilder.minRate(queueMinRate)
        val queueUUID = queueBuilder.build
        queueBuilder.clear
        assertTrue(ovsdb.hasQueue(queueUUID))
        qosBuilder = ovsdb.updateQos(
            qosUUID, queueUUIDs = Some(Map((1: Long) -> queueUUID)))
        qosBuilder.update(qosUUID)
        assertFalse(ovsdb.isEmptyColumn(TableQos, qosUUID, ColumnQueues))
        val retrievedQueueUUID = ovsdb.getQueueUUIDByQueueNum(qosUUID, (1: Long))
        assertFalse(retrievedQueueUUID.isEmpty)
        assertEquals(queueUUID, retrievedQueueUUID.get)
        ovsdb.clearQosQueues(qosUUID)
        ovsdb.delQueue(queueUUID)
        ovsdb.delQos(qosUUID)
        assertFalse(ovsdb.hasQos(qosUUID))
    }

      /**
       * Test getQueueExternalIdByQueueNum
     */
    @Test def testGetQueueExternalIdByQueueNum() = {
        val qosType = "linux-htb"
        val queueExtIdKey = bridgeExtIdKey
        val queueExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        var qosBuilder = ovsdb.addQos(qosType)
        val qosUUID = qosBuilder.build
        qosBuilder.clear
        assertTrue(ovsdb.hasQos(qosUUID))
        val queueMinRate: Long = 100000000
        var queueBuilder = ovsdb.addQueue()
        queueBuilder.minRate(queueMinRate)
        queueBuilder.externalId(queueExtIdKey, queueExtIdValue)
        val queueUUID = queueBuilder.build
        queueBuilder.clear
        assertTrue(ovsdb.hasQueue(queueUUID))
        qosBuilder = ovsdb.updateQos(
            qosUUID, queueUUIDs = Some(Map((1: Long) -> queueUUID)))
        qosBuilder.update(qosUUID)
        assertFalse(ovsdb.isEmptyColumn(TableQos, qosUUID, ColumnQueues))
        val retrievedQueueExtIdValue = ovsdb.getQueueExternalIdByQueueNum(
            qosUUID, (1: Long), queueExtIdKey)
        assertFalse(retrievedQueueExtIdValue.isEmpty)
        assertEquals(queueExtIdValue, retrievedQueueExtIdValue.get)
        ovsdb.clearQosQueues(qosUUID)
        ovsdb.delQueue(queueUUID)
        ovsdb.delQos(qosUUID)
        assertFalse(ovsdb.hasQos(qosUUID))
    }
}
