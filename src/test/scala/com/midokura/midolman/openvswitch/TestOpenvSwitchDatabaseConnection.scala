/**
 * TestOpenvSwitchDatabaaseConnection.scala - OVSDB connection Test.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.openvswitch


import org.junit.{AfterClass, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.slf4j.LoggerFactory

import com.midokura.midolman.openvswitch.OpenvSwitchException._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._

import scala.collection.JavaConversions._


/**
 * Test for the Open vSwitch database connection.
 */
object TestOpenvSwitchDatabaseConnection 
        extends OpenvSwitchDatabaseConnectionBridgeConnector {
    final val log = LoggerFactory.getLogger(
                                classOf[TestOpenvSwitchDatabaseConnection])

    private final val portName = "testovsport"
    private final val bridgeOfPortNum = 65534
    private final val oldQosExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
    private final val newQosExtIdValue = "002bcb5f-0000-8000-1000-foobarbuzqux"

    override final val bridgeName = "testovsbr"
    override final val bridgeExtIdKey = "midolman-vnet"
    override final val bridgeExtIdValue = "efbf1194-9e25-11e0-b3b3-ba417460eb69"
    override final val bridgeId: Long = 0x15b138e7fa339bbcL

    @AfterClass def finalizeTest() { disconnectFromOVSDB }

    @BeforeClass def initializeTest() { 
        connectToOVSDB
        log.debug("Successfully connected to OVSDB.")

        val qosTable = ovsdb.dumpQosTable
        for { row <- qosTable
            extIds = row._3.get(1).getElements
        } { 
            log.debug("QoS table row: {}", row)
            for { extId <- extIds 
                if extId.get(0).getTextValue == bridgeExtIdKey && (
                       extId.get(1).getTextValue == oldQosExtIdValue ||
                       extId.get(1).getTextValue == newQosExtIdValue)
            } {
                log.debug("extId: {}", extId)
                log.info("Deleting preexisting test QoS {} => {}",
                         row._1, extId.get(1).getTextValue)
                ovsdb.delQos(row._1)
            }
        }
    }

}

class TestOpenvSwitchDatabaseConnection {
    import TestOpenvSwitchDatabaseConnection._

    @Test def testAddSystemPortNoLeftoverIface() {
        log.debug("Entering testAddSystemPortNoLeftoverIface")
        portName.synchronized {
            assertFalse(ovsdb.hasInterface(portName))
            try {
                var pb = ovsdb.addSystemPort(bridgeName, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
                assertTrue(ovsdb.hasInterface(portName))
                log.debug("Calling ovsdb.delPort({})", portName)
            } finally {
                ovsdb.delPort(portName)
                log.debug("Back from ovsdb.delPort()")
                assertFalse(ovsdb.hasPort(portName))
                assertFalse(ovsdb.hasInterface(portName))
                log.debug("Leaving testAddSystemPortNoLeftoverIface")
            }
        }
    }

    @Test(expected = classOf[DuplicatedRowsException])
    def testDelTwoSystemPorts() {
        portName.synchronized {
            assertFalse(ovsdb.hasPort(portName))
            try {
                ovsdb.addSystemPort(bridgeName, portName).build
                ovsdb.addSystemPort(bridgeName, portName).build
                assertTrue(ovsdb.hasPort(portName))
                assertEquals(2, ovsdb.numPortsWithName(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        }
    }
            
    /**
     * Test addSystemPort().
     */
    @Test def testAddSystemPortBridgeName() {
        portName.synchronized {
            try {
                var pb = ovsdb.addSystemPort(bridgeName, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        }
    }

    @Test def testAddSystemPortBridgeId() {
        portName.synchronized {
            try {
                val pb = ovsdb.addSystemPort(bridgeId, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        }
    }

    /**
     * Test addInternalPort().
     */
    @Test def testAddInternalPort() {
        portName.synchronized {
            try {
                val pb = ovsdb.addInternalPort(bridgeName, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
            try {
                val pb = ovsdb.addInternalPort(bridgeId, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        }
    }

    /**
     * Test addTapPort().
     */
    @Test(timeout=5000) def testAddTapPort() {
        portName.synchronized {
            try {
                val pb = ovsdb.addTapPort(bridgeName, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
            // Test adding a tap interface created with ip command.
            try {
                log.debug("Add tap port {} with ip command", portName)
                var ipCmd = Runtime.getRuntime().exec(
                    "sudo -n ip tuntap add dev %s mode tap".format(portName))
                ipCmd.waitFor
                assertEquals(ipCmd.exitValue, 0)
                ipCmd = Runtime.getRuntime().exec(
                    "sudo -n ip link set dev %s arp on mtu 1300 multicast off up"
                    .format(portName));
                ipCmd.waitFor
                assertEquals(ipCmd.exitValue, 0)
                val pb = ovsdb.addTapPort(bridgeName, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                log.debug("Delete tap port {} with ip command", portName)
                val ipCmd = Runtime.getRuntime().exec(
                    "sudo -n ip link del %s".format(portName))
                ipCmd.waitFor
                assertEquals(ipCmd.exitValue, 0)
                assertFalse(ovsdb.hasPort(portName))
            }
            try {
                val pb = ovsdb.addTapPort(bridgeId, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
            try {
                log.debug("Add tap port {} with ip command", portName)
                var ipCmd = Runtime.getRuntime().exec(
                    "sudo -n ip tuntap add dev %s mode tap".format(portName))
                ipCmd.waitFor
                assertEquals(ipCmd.exitValue, 0)
                ipCmd = Runtime.getRuntime().exec(
                    "sudo -n ip link set dev %s arp on mtu 1300 multicast off up"
                    .format(portName));
                ipCmd.waitFor
                assertEquals(ipCmd.exitValue, 0)
                val pb = ovsdb.addTapPort(bridgeId, portName)
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                log.debug("Delete tap port {} with ip command", portName)
                val ipCmd = Runtime.getRuntime().exec(
                    "sudo -n ip link del %s".format(portName))
                ipCmd.waitFor
                assertEquals(ipCmd.exitValue, 0)
                assertFalse(ovsdb.hasPort(portName))
            }
        }
    }

    /**
     * Test addGrePort().
     */
    @Test def testAddGrePort() {
        portName.synchronized {
            try {
                val pb = ovsdb.addGrePort(bridgeName, portName, "127.0.0.1")
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
            try {
                val pb = ovsdb.addGrePort(bridgeId, portName, "127.0.0.1")
                pb.build
                assertTrue(ovsdb.hasPort(portName))
            } finally {
                if (ovsdb.hasPort(portName))
                    ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        }
    }

    @Test(expected=classOf[DuplicatedRowsException])
    def deleteDuppedGrePorts() {
        portName.synchronized {
            try {
                ovsdb.addGrePort(bridgeName, portName, "127.0.0.1").build
                ovsdb.addGrePort(bridgeName, portName, "127.0.0.1").build
                assertEquals(2, ovsdb.numPortsWithName(portName))
            } finally {
                if (ovsdb.hasPort(portName))
                    ovsdb.delPort(portName)
                if (ovsdb.hasPort(portName))
                    ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        }
    }

    /**
     * Test addOpenflowController().
     */
    @Test def testAddOpenflowController() {
        try {
            val cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
            cb.build
            assertTrue(ovsdb.hasController(target))
        } finally {
            ovsdb.delBridgeOpenflowControllers(bridgeName)
            assertFalse(ovsdb.hasController(target))
        }
        try {
            val cb = ovsdb.addBridgeOpenflowController(bridgeId, target)
            cb.build
            assertTrue(ovsdb.hasController(target))
        } finally {
            ovsdb.delBridgeOpenflowControllers(bridgeId)
            assertFalse(ovsdb.hasController(target))
        }
    }

    /**
     * Test getDatapathExternalId().
     */
    @Test def testGetDatapathExternalId() {
        assertTrue(ovsdb.hasBridge(bridgeName))
        assertEquals(ovsdb.getDatapathExternalId(bridgeName, bridgeExtIdKey),
                     bridgeExtIdValue)
        assertEquals(ovsdb.getDatapathExternalId(bridgeId, bridgeExtIdKey),
                     bridgeExtIdValue)
    }

    /**
     * Test getPortExternalId().
     */
    @Test def testGetPortExternalId() {
        assertEquals(ovsdb.getPortExternalId(bridgeName, bridgeOfPortNum,
                                             bridgeExtIdKey), null)
        assertEquals(ovsdb.getPortExternalId(bridgeId, bridgeOfPortNum,
                                             bridgeExtIdKey), null)

        val portExtIdKey = bridgeExtIdKey
        val portExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        try {
            val pb = ovsdb.addSystemPort(bridgeName, portName)
            pb.externalId(portExtIdKey, portExtIdValue)
            pb.build
            assertTrue(ovsdb.hasPort(portName))
            assertEquals(ovsdb.getPortExternalId(portName, portExtIdKey),
                         portExtIdValue)
        } finally {
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        }
    }

    /**
     * Test getBridgeNamesByExternalId().
     */
    @Test def testGetBridgeNamesByExternalId() {
        assertTrue(ovsdb.hasBridge(bridgeName))
        assertTrue(ovsdb.getBridgeNamesByExternalId(
            bridgeExtIdKey, bridgeExtIdValue).contains(bridgeName))
    }

    /**
     * Test getPortNamesByExternalId().
     */
    @Test def testGetPortNamesByExternalId() {
        val portExtIdKey = bridgeExtIdKey
        val portExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
        try {
            var pb = ovsdb.addSystemPort(bridgeName, portName)
            pb.externalId(portExtIdKey, portExtIdValue)
            pb.build
            assertTrue(ovsdb.getPortNamesByExternalId(
                portExtIdKey, portExtIdValue).contains(portName))
        } finally {
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        }
    }

    /**
     * Test addQos and delQos.
     */
    @Test def testAddQos() {
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
    @Test def testUpdateQos() {
        log.debug("Entering testUpdateQos")
        val qosType = "linux-htb"
        val qosExtIdKey = bridgeExtIdKey
        var qb = ovsdb.addQos(qosType)
        qb.externalId(qosExtIdKey, oldQosExtIdValue)
        val uuid = qb.build
        assertTrue(ovsdb.hasQos(uuid))
        assertFalse(ovsdb.getQosUUIDsByExternalId(
                                qosExtIdKey, oldQosExtIdValue).isEmpty)
        qb = ovsdb.updateQos(uuid,
            externalIds=Some(Map(qosExtIdKey -> newQosExtIdValue)))
        qb.update(uuid)
        assertTrue(ovsdb.getQosUUIDsByExternalId(
            qosExtIdKey, oldQosExtIdValue).isEmpty)
        assertFalse(ovsdb.getQosUUIDsByExternalId(
            qosExtIdKey, newQosExtIdValue).isEmpty)
        ovsdb.delQos(uuid)
        assertFalse(ovsdb.hasQos(uuid))
        log.debug("Leaving testUpdateQos")
    }

    /**
     * Test addQueue and delQueue.
     */
    @Test def testAddQueue() {
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
    @Test def testSetPortQos() {
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
    @Ignore @Test def testUpdateQueue() {
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
    @Test def testClearQosQueeus() {
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
    @Test def testGetQueueUUIDByQueueNum() {
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
    @Test def testGetQueueExternalIdByQueueNum() {
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
