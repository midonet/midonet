/**
 * CheckOpenvSwitchDatabaaseConnection.scala - OVSDB connection Test.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.openvswitch


import org.junit.{AfterClass, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.slf4j.LoggerFactory

import com.midokura.midolman.openvswitch._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._

import scala.collection.JavaConversions._


/**
 * Test for the Open vSwitch database connection.
 */
object CheckOpenvSwitchDatabaseConnection {
    import TestShareOneOpenvSwitchDatabaseConnection._

    final val log = LoggerFactory.getLogger(
                                classOf[CheckOpenvSwitchDatabaseConnection])

    private final val portName = "testovsport"
    private final val bridgeOfPortNum = 65534
    private final val oldQosExtIdValue = "002bcb5f-0000-8000-1000-bafbafbafbaf"
    private final val newQosExtIdValue = "002bcb5f-0000-8000-1000-foobarbuzqux"

    @BeforeClass def before() { 
        mutex.acquire 
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
        val qosTable = ovsdb.dumpQosTable
        for { row <- qosTable
            extIds = row._3.get(1).getElements
        } { 
            log.debug("QoS table row: {}", row)
            for { extId <- extIds 
                if extId.get(0).getTextValue == "midolman-vnet" && (
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

    @AfterClass def after() { 
        mutex.release 
        finishedSemaphore.release
    }
}

class CheckOpenvSwitchDatabaseConnection {
    import CheckOpenvSwitchDatabaseConnection._
    // Share a common OVSDB connection because using two breaks.
    import TestShareOneOpenvSwitchDatabaseConnection._

    @Test def testAddSystemPortNoLeftoverIface() {
        log.debug("Entering testAddSystemPortNoLeftoverIface")
        assertFalse(ovsdb.hasInterface(portName))
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        assertTrue(ovsdb.hasInterface(portName))
        log.debug("Calling ovsdb.delPort({})", portName)
        ovsdb.delPort(portName)
        log.debug("Back from ovsdb.delPort()")
        assertFalse(ovsdb.hasPort(portName))
        assertFalse(ovsdb.hasInterface(portName))
        log.debug("Leaving testAddSystemPortNoLeftoverIface")
    }

    /**
     * Test addSystemPort().
     */
    @Test def testAddSystemPort() {
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addInternalPort().
     */
    @Test def testAddInternalPort() {
        var pb = ovsdb.addInternalPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        pb = ovsdb.addInternalPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addTapPort().
     */
    @Test def testAddTapPort() {
        var pb = ovsdb.addTapPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        pb = ovsdb.addTapPort(bridgeName, portName)
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addGrePort().
     */
    @Test def testAddGrePort() {
        var pb = ovsdb.addGrePort(bridgeName, portName, "127.0.0.1")
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))

        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        pb = ovsdb.addGrePort(bridgeName, portName, "127.0.0.1")
        pb.build
        assertTrue(ovsdb.hasPort(portName))
        ovsdb.delPort(portName)
        assertFalse(ovsdb.hasPort(portName))
    }

    /**
     * Test addOpenflowController().
     */
    @Test def testAddOpenflowController() {
        var cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
        cb.build
        assertTrue(ovsdb.hasController(target))
        ovsdb.delBridgeOpenflowControllers(bridgeName)
        assertFalse(ovsdb.hasController(target))

        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
        cb.build
        assertTrue(ovsdb.hasController(target))
        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        ovsdb.delBridgeOpenflowControllers(bridgeName)
        assertFalse(ovsdb.hasController(target))
    }

    /**
     * Test getDatapathExternalId().
     */
    @Test def testGetDatapathExternalId() {
        assertTrue(ovsdb.hasBridge(bridgeName))
        assertEquals(ovsdb.getDatapathExternalId(bridgeName, bridgeExtIdKey),
                     bridgeExtIdValue)
        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        //assertEquals(ovsdb.getDatapathExternalId(bridgeId, bridgeExtIdKey),
        //             bridgeExtIdValue)
    }

    /**
     * Test getPortExternalId().
     */
    @Test def testGetPortExternalId() {
        assertEquals(ovsdb.getPortExternalId(bridgeName, bridgeOfPortNum,
                                             bridgeExtIdKey), null)
        // TODO(jlm, tfukushima): Test using bridgeId once that's reliable.
        //assertEquals(ovsdb.getPortExternalId(bridgeId, bridgeOfPortNum,
        //                                     bridgeExtIdKey), null)

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
    @Test def testUpdateQueue() {
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
