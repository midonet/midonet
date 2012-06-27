/**
 * TestBridgeOVS.scala - Test BridgeController's interactions with
 * Open vSwitch.
 *
 * Copyright 2012 Midokura Inc.  All rights reserved.
 */

package com.midokura.midolman

import scala.collection.JavaConversions._
import scala.collection.mutable
import java.net.InetSocketAddress
import java.nio.channels.{SelectionKey, ServerSocketChannel}
import java.util.concurrent.{Executors, TimeUnit, ScheduledFuture, Semaphore}
import java.util.UUID

import org.openflow.protocol.{OFMatch, OFPort}
import org.openflow.protocol.action.OFActionEnqueue
import org.openflow.protocol.statistics.{
OFAggregateStatisticsReply, OFDescriptionStatistics, OFFlowStatisticsReply,
OFPortStatisticsReply, OFQueueStatisticsReply,
OFTableStatistics}
import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.slf4j.LoggerFactory

import com.midokura.midolman.eventloop.{SelectListener, SelectLoop}
import com.midokura.midolman.openflow.ControllerStubImpl
import com.midokura.midolman.openvswitch.{
OpenvSwitchDatabaseConnectionImpl,
OpenvSwitchDatabaseConnectionBridgeConnector}
import com.midokura.midolman.packets.MAC
import com.midokura.midolman.packets.IntIPv4
import com.midokura.midolman.state.{BridgeZkManager, MacPortMap, MockDirectory,
PortToIntNwAddrMap, ZkPathManager}
import com.midokura.midolman.openflow.OpenFlowError
import com.midokura.midolman.vrn.VRNController


class ChattySemaphore(capacity: Int) extends Semaphore(capacity) {
    final val log = LoggerFactory.getLogger(classOf[ChattySemaphore])

    override def release() {
        super.release()
        log.info("release: {}", this)
    }

    override def acquire() {
        super.acquire()
        log.info("acquire: {}", this)
    }
}


/**
 * Test the BridgeController's interaction with Open vSwitch.
 */
object TestBridgeOVS extends OpenvSwitchDatabaseConnectionBridgeConnector
with SelectListener {
    // All the "static" variables and methods.
    final val log = LoggerFactory.getLogger(classOf[TestBridgeOVS])
    private final val testPortName = "testbrport"
    private final val publicIP = IntIPv4.fromString("192.168.1.50")
    private final var controller: BridgeControllerTester = _
    private final val zkDir = new MockDirectory
    private final val zkRoot = "/zk_root"
    private final val of_port = 6635
    private final var listenSock: ServerSocketChannel = _
    private final var reactor: SelectLoop = _
    private final var tookTooLong: ScheduledFuture[_] = _
    private final var reactorThread: Thread = _
    private final val serializeTestsSemaphore = new ChattySemaphore(0)
    private final val portModSemaphore = new ChattySemaphore(0)
    private final val connectionSemaphore = new ChattySemaphore(0)
    @volatile private var tooLongFlag = false

    override final val bridgeName = "testbr"
    override final val bridgeExtIdKey = "midolman-vnet"
    override final val bridgeExtIdValue = "ebbf1184-4dc2-11e0-b2c3-a4b17460e319"
    override final val bridgeId: Long = 0x74a027d6e9288adbL
    private final val portName = "testport"
    private final var qosUUID: String = _
    private final val qosExtIdKey = "test-midolman-vnet-qos"
    private final val qosExtIdVal = UUID.randomUUID().toString
    private final var queueUUID: String = _
    private final val queueExtIdKey = "test-midolman-vnet-queue"
    private final val queueExtIdVal = UUID.randomUUID().toString

    @BeforeClass def initializeTest() {
        connectToOVSDB

        // Set up the (mock) ZooKeeper directories.
        val vrnPortLocKey = "/vrn_port_locations"
        val noData = Array[Byte]()
        val midoDirName = zkDir.add(zkRoot, noData, CreateMode.PERSISTENT)
        val midoDir = zkDir.getSubDirectory(midoDirName)
        Setup.createZkDirectoryStructure(zkDir, zkRoot)
        val portLocMap = new PortToIntNwAddrMap(
            midoDir.getSubDirectory(vrnPortLocKey))
        portLocMap.start()
        reactor = new SelectLoop(Executors.newScheduledThreadPool(1))

        val bcfg = new BridgeZkManager.BridgeConfig()
        val bzkm = new BridgeZkManager(zkDir, zkRoot)
        val bridgeUUID = bzkm.create(bcfg)
        val pathMgr = new ZkPathManager(zkRoot)
        val macPortKey = pathMgr.getBridgeMacPortsPath(bridgeUUID)
        val macPortMap = new MacPortMap(
            zkDir.getSubDirectory(macPortKey))
        val vrnID = UUID.randomUUID()
        macPortMap.start()

        reactorThread = new Thread() {
            override def run() {
                log.info("reactorThread starting")

                controller = new BridgeControllerTester(
                    bridgeId /* datapathId */ ,
                    zkDir /* zkDir */ ,
                    zkRoot /* basePath */ ,
                    publicIP /* publicIp */ ,
                    ovsdb /* ovsdb */ ,
                    reactor /* reactor */ ,
                    bridgeExtIdKey /* externalIdKey */ ,
                    vrnID /* VRN ID */ ,
                    portModSemaphore /* portSemaphore */ ,
                    connectionSemaphore /* connectionSemaphore */)

                // Get a connection to the OF switch.
                listenSock = ServerSocketChannel.open
                listenSock.configureBlocking(false)
                listenSock.socket.bind(new InetSocketAddress(of_port))

                reactor.register(listenSock, SelectionKey.OP_ACCEPT,
                    TestBridgeOVS.this)

                registerController

                tookTooLong = reactor.schedule(
                    new Runnable() {
                        def run() {
                            log.info("Took too long!")
                            tooLongFlag = true
                            reactor.shutdown()
                            ovsdb.close
                            portModSemaphore.release(10)
                            connectionSemaphore.release
                        }
                    },
                    10000, TimeUnit.MILLISECONDS)
                reactor.doLoop()
                log.info("reactor thread exiting")
            }
        }

        reactorThread.start()

        addPortWithQosAndQueue(portName)
        assertTrue(ovsdb.hasPort(portName))
        log.info("Port {} successfully added to ovsdb", portName)
        log.info("Leaving initializeTest()")
    }

    @AfterClass def finalizeTest() {
        try {
            if (null != ovsdb) {
                reactor.shutdown()
                assertFalse(tooLongFlag)
                assertTrue(ovsdb.hasController(target))
                assertTrue(ovsdb.hasBridge(bridgeId))
                ovsdb.delBridgeOpenflowControllers(bridgeId)
                assertFalse(ovsdb.hasController(target))
                // Queue
                assertTrue(ovsdb.hasQueue(queueUUID))
                ovsdb.clearQosQueues(qosUUID)
                assertTrue(ovsdb.isEmptyColumn("QoS", qosUUID, "queues"))
                ovsdb.delQueue(queueUUID)
                assertFalse(ovsdb.hasQueue(queueUUID))
                // QoS
                assertTrue(ovsdb.hasQos(qosUUID))
                ovsdb.unsetPortQos(portName)
                ovsdb.delQos(qosUUID)
                assertFalse(ovsdb.hasQos(qosUUID))
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        } finally {
            disconnectFromOVSDB
        }
    }

    def registerController() {
        val cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
        cb.build()
        assertTrue(ovsdb.hasController(target))
    }

    def handleEvent(key: SelectionKey) {
        log.info("handleEvent {}", key)

        val sock = listenSock.accept
        if (sock == null) {
            log.info("Couldn't accept connection -- isAcceptable() = {}",
                key.isAcceptable)
            return
        }
        log.info("accepted connection from {}",
            sock.socket.getRemoteSocketAddress)
        sock.socket.setTcpNoDelay(true)
        sock.configureBlocking(false)

        val controllerStub = new ControllerStubImpl(sock, reactor, controller)
        controller.setControllerStub(controllerStub)
        reactor.register(sock, SelectionKey.OP_READ, controllerStub)
        reactor.wakeup()
        controllerStub.start()
    }

    def addSystemPort(portName: String, vportId: UUID) {
        val pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.ifMac("00:01:02:03:04:05")
        pb.externalId(bridgeExtIdKey, vportId.toString)
        pb.build()
    }

    def addInternalPort(portName: String) {
        ovsdb.addInternalPort(bridgeName, portName).build()
    }

    def addPortWithQosAndQueue(portName: String) {
        val queues = ovsdb.addQueue().maxRate(1000000)
        queues.externalId(queueExtIdKey, queueExtIdVal).build
        val queueUUIDs: List[String] = (ovsdb.getQueueUUIDsByExternalId(
            queueExtIdKey, queueExtIdVal): mutable.Set[String]).toList
        queueUUID = queueUUIDs(0)
        val qos = ovsdb.addQos("linux-hfsc").queues(
            (mutable.Map(
                (0L: java.lang.Long) -> (queueUUID: java.lang.String))))
        qos.externalId(qosExtIdKey, qosExtIdVal).build
        val qosUUIDs: List[String] = (ovsdb.getQosUUIDsByExternalId(
            qosExtIdKey, qosExtIdVal): mutable.Set[String]).toList
        qosUUID = qosUUIDs(0)
        ovsdb.addInternalPort(bridgeName, portName).qos(qosUUIDs(0)).build()
    }

    def addTapPort(portName: String, vportId: UUID) {
        val pb = ovsdb.addTapPort(bridgeName, portName)
        pb.externalId(bridgeExtIdKey, vportId.toString)
        pb.build()
    }
}

class TestBridgeOVS {
    // import all the statics.
    import TestBridgeOVS._

    @Test def testConnectionMade() {
        // Ensure that this runs first, by having the other tests block on
        // serializeTestsSemaphore, which this routine .releases.
        log.info("testConnectionMade")
        // Wait for the connection to be established.
        connectionSemaphore.acquire
        // Drain the portModSemaphore from the ports of the initial connection.
        portModSemaphore.drainPermits
        serializeTestsSemaphore.release
    }

    @Ignore
    @Test def testNewSystemPort() {
        log.info("testNewSystemPort called")
        serializeTestsSemaphore.acquire
        try {
            log.info("testNewSystemPort has semaphore")
            val portName = "sys" + testPortName
            // Clear the list of added ports, and make a new port which should
            // trigger an addPort callback.
            controller.addedPorts = List()
            val portId = UUID.randomUUID()
            addSystemPort(portName, portId)
            assertTrue(ovsdb.hasPort(portName))
            log.info("Port {} successfully added to ovsdb", portName)
            // TODO: Verify this is a system port.
            portModSemaphore.acquire
            assertEquals(1, controller.addedPorts.size)
            assertEquals(portId, controller.addedPorts(0))
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
            controller.addedPorts = List()
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewSystemPort exiting")
        }
    }

    @Test(timeout = 1000) def testGetDescStats() {
        log.info("testGetDescStats")
        try {
            val response = controller.getDescStatsReply(controller.sendDescStatsRequest())
            log.info("Controller got the response: {}", response)
            for (reply: OFDescriptionStatistics <- response) {
                assertEquals(reply.getManufacturerDescription,
                    "Nicira Networks, Inc.")
                assertEquals(reply.getHardwareDescription,
                    "Open vSwitch")
            }
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetDescStats exiting")
        }
    }

    // TODO(tfukushima): Add a unit test for getFlowStats.
    @Ignore
    @Test(timeout = 1000) def testGetFlowStats() {
        log.info("testGetFlowStatas")
        try {
            val ofMatch = new OFMatch()
            val ofActionEnqueue = new OFActionEnqueue
            val portNum = ovsdb.getPortNumsByPortName(
                portName).filter(_ > 0).head
            ofActionEnqueue.setPort(portNum)
            ofActionEnqueue.setQueueId(ovsdb.getQueueNumByQueueUUID(
                qosUUID, queueUUID))
            // val ofActions = List(ofActionEnqueue)
            // Add flow such as...
            // controller.addFlow(ofmatch, 0,
            //     1000, 1000, 0,
            //     0, true, true, false, ofActions)
            val reply = controller.getFlowStatsReply(
                controller.sendFlowStatsRequest(ofMatch, 0xff.toByte, OFPort.OFPP_NONE.getValue)
            )
            log.info("Controller got the response: {}", reply)
            for (response: OFFlowStatisticsReply <- reply)
                assertNotSame(0, response.getDurationNanoseconds)
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetFlowStats exiting")
        }
    }

    @Test(timeout = 1000) def testGetAggregateStats() {
        log.info("testAggregateStats")
        try {
            val ofMatch = new OFMatch()
            val response = controller.getAggregateStatsReply(
                controller.sendAggregateStatsRequest(ofMatch, 0xff.toByte,
                    ovsdb.getPortNumByUUID(ovsdb.getPortUUID(portName))))
            log.info("Controller got the response: {}", response)
            for (reply: OFAggregateStatisticsReply <- response)
                assertEquals(0, reply.getFlowCount)
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetAggregateStats exiting")
        }
    }

    @Ignore
    @Test(timeout = 1000) def testTableStats() {
        log.info("testTableStats")
        try {
            val response = controller.getTableStatsReply(
                controller.sendTableStatsRequest()
            )
            log.info("Controlelr got the response {}", response)
            for (reply: OFTableStatistics <- response) {
                assertEquals(reply.getTableId, 1)
                assertNotSame(reply.getActiveCount, 0)
            }
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testTableStats exiting")
        }
    }

    @Test(timeout = 1000) def testGetPortStats() {
        log.info("testGetPortStats")
        try {
            val portNum = ovsdb.getPortNumsByPortName(
                portName).filter(_ > 0).head
            val reply =
                controller.getPortStatsReply(
                    controller.sendPortStatsRequest(portNum))
            for (response: OFPortStatisticsReply <- reply) {
                log.info("Controller got the response: {}",
                    response.getPortNumber)
                assertEquals(response.getPortNumber, portNum)
            }
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetPortStats exiting")
        }
    }

    @Test(timeout = 1000) def testGetQueueStats() {
        log.info("testGetQueueStats")
        try {
            val portNum = ovsdb.getPortNumsByPortName(
                portName).filter(_ > 0).head
            val reply = controller.getQueueStatsReply(
                controller.sendQueueStatsRequest(portNum,
                    ovsdb.getQueueNumByQueueUUID(qosUUID, queueUUID)))
            for (response: OFQueueStatisticsReply <- reply) {
                log.info("Controller got the response: {}",
                    response.getQueueId)
                assertEquals(response.getPortNumber, portNum)
            }
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetQueueStats exiting")
        }
    }

    @Test def testNewInternalPort() {
        log.info("testNewInternalPort")
        serializeTestsSemaphore.acquire
        log.info("testNewInternalPort has semaphore")
        try {
            val oldNumDownPorts = controller.getDownPorts.size
            val portName = "int" + testPortName
            // Clear the list of added ports, and make a new port which should
            // go into the downPorts list.
            controller.addedPorts = List()
            addInternalPort(portName)
            assertTrue(ovsdb.hasPort(portName))
            portModSemaphore.acquire
            assertEquals(oldNumDownPorts + 1, controller.getDownPorts.size)
            //assertEquals(portName, controller.addedPorts(0).getName)
            // TODO: Verify this is an internal port.
            // TODO: Try this with a port which is up.
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewInternalPort exiting")
        }
    }

    @Ignore
    @Test def testNewTapPort() {
        log.info("testNewTapPort")
        serializeTestsSemaphore.acquire
        log.info("testNewTapPort has semaphore")
        try {
            val portName = "tap" + testPortName
            val portId = UUID.randomUUID()
            addTapPort(portName, portId)
            // Clear the list of added ports, and make a new port which should
            // trigger an addPort callback.
            controller.addedPorts = List()
            assertTrue(ovsdb.hasPort(portName))
            portModSemaphore.acquire
            assertEquals(1, controller.addedPorts.size)
            assertEquals(portName, controller.addedPorts(0))
            // TODO: Verify this is a TAP port.
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewTapPort exiting")
        }
    }
}


// Used for the downPorts set, so that adding a port will release a semaphore,
// whether the port is up (in addPort) or down (here).
private class NotifyingSet(val portSemaphore: Semaphore)
    extends java.util.HashSet[java.lang.Integer]() {
    override def add(i: java.lang.Integer) = {
        try {
            super.add(i)
        }
        finally {
            portSemaphore.release()
        }
    }
}

private class BridgeControllerTester(datapathId: Long,
                                     zkDir: MockDirectory, basePath: String, publicIP: IntIPv4,
                                     ovsdb: OpenvSwitchDatabaseConnectionImpl, reactor: SelectLoop,
                                     externalIDKey: String, vrnId: UUID, portSemaphore: Semaphore,
                                     connectionSemaphore: Semaphore) extends
VRNController(zkDir, basePath, publicIP, ovsdb,
    reactor, null, externalIDKey, vrnId, false, null,
    null, 1450) {
    downPorts = new NotifyingSet(portSemaphore)
    var addedPorts = List[UUID]()
    final val log = LoggerFactory.getLogger(classOf[BridgeControllerTester])

    def getDownPorts = {
        downPorts
    }

    override def onConnectionMade() {
        try {
            log.info("onConnectionMade")
            super.onConnectionMade()
        } finally {
            connectionSemaphore.release()
        }
    }

    override def addVirtualPort(portNum: Int, name: String, addr: MAC,
                                vId: UUID) {
        try {
            log.info("addVirtualPort")
            super.addVirtualPort(portNum, name, addr, vId)
            addedPorts ::= vId
        } finally {
            portSemaphore.release()
        }
    }
}
