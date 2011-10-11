/**
 * TestBridgeControllerOVS.scala - Test BridgeController's interactions with
 *                                 Open vSwitch.
 *
 * Copyright 2011 Midokura Inc.  All rights reserved.
 */

package com.midokura.midolman

import com.midokura.midolman.eventloop.{SelectListener, SelectLoop}
import com.midokura.midolman.openflow.ControllerStubImpl
import com.midokura.midolman.openvswitch.{
                BridgeBuilder, 
                OpenvSwitchDatabaseConnectionImpl, 
                TestShareOneOpenvSwitchDatabaseConnection}
import com.midokura.midolman.packets.IntIPv4
import com.midokura.midolman.state.{MacPortMap, MockDirectory, 
                                    PortToIntNwAddrMap}

import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.junit.Assume._
import org.openflow.protocol.OFPhysicalPort
import org.slf4j.LoggerFactory

import java.io.{File, RandomAccessFile}
import java.lang.Long.parseLong
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.channels.{FileLock, SelectionKey, ServerSocketChannel}
import java.util.concurrent.{Executors, TimeUnit, ScheduledFuture, Semaphore}
import java.util.{Date, UUID}

class ChattySemaphore(capacity: Int) extends Semaphore(capacity) {
    final val log = LoggerFactory.getLogger(classOf[ChattySemaphore])

    override def release() {
        super.release
        log.info("release: {}", this)
    }

    override def acquire() {
        super.acquire
        log.info("acquire: {}", this)
    }
}
    
/**
 * Test the BridgeController's interaction with Open vSwitch.
 */
object TestBridgeControllerOVS extends SelectListener {
    // All the "static" variables and methods.
    final val log = LoggerFactory.getLogger(classOf[TestBridgeControllerOVS])
    private final val testportName = "testbrport"
    private final val publicIP = IntIPv4.fromString("192.168.1.50")
    private final var controller: BridgeControllerTester = _
    private var zkDir = new MockDirectory
    private final val zkRoot = "/zk_root"
    private final val of_port = 6635
    private final var listenSock: ServerSocketChannel = _
    private final var reactor: SelectLoop = _
    private final var tookTooLong: ScheduledFuture[_] = _
    private final var reactorThread: Thread = _
    private final var serializeTestsSemaphore = new ChattySemaphore(0)
    private final var portModSemaphore = new ChattySemaphore(0)
    private final var connectionSemaphore = new ChattySemaphore(0)
    @volatile private var tooLongFlag = false

    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    final val bridgeName = "testbr"
    final val bridgeExtIdKey = "midolman-vnet"
    final val bridgeExtIdValue = "ebbf1184-4dc2-11e0-b2c3-a4b17460e319"
    // final var bridgeId: Long = 0x74a027d6e9288adbL;
    final var bridgeId: Long = 0x74a027d6L;
    final var ovsdb: OpenvSwitchDatabaseConnectionImpl = _
    final val target = "tcp:127.0.0.1:6635"
    private final var lockfile = new File("/tmp/ovsdbconnection.lock")
    private var lock: FileLock = _

    def testAddBridge() {
        val bb: BridgeBuilder = ovsdb.addBridge(bridgeName)
        bb.externalId(bridgeExtIdKey, bridgeExtIdValue)
        bb.datapathId(bridgeId)
        bb.build
        assertTrue(ovsdb.hasBridge(bridgeName))
    }

    def testDelBridge() {
        ovsdb.delBridge(bridgeName)
        assertFalse(ovsdb.hasBridge(bridgeName))
    }

    def connectToOVSDB() {
        lockfile.setReadable(true, false)
        lockfile.setWritable(true, false)
        lock = new RandomAccessFile(lockfile, "rw").getChannel.lock
        try {
            ovsdb = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        } catch {
            case e: ConnectException =>
                assumeNoException(e)
        }
        val bridgeTable = ovsdb.dumpBridgeTable
        for { row <- bridgeTable
            if row._1.startsWith("test")
        } { log.info("Deleting preexisting test Bridge {} => {}",
                     row._2, row._1)
            ovsdb.delBridgeUUID(row._2, row._3)
        }
        testAddBridge
        assertEquals(bridgeId, parseLong(ovsdb.getDatapathId(bridgeName), 16))
        ovsdb.delTargetOpenflowControllers(target)
        assertFalse(ovsdb.hasController(target))
    }

    @BeforeClass def initializeTest() {
        connectToOVSDB

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
        reactor = new SelectLoop(Executors.newScheduledThreadPool(1))

        reactorThread = new Thread() { override def run() {
            log.info("reactorThread starting")

            controller = new BridgeControllerTester(
                /* datapathId */              bridgeId,
                /* switchUuid */              UUID.fromString(bridgeExtIdValue),
                /* greKey */                  0xe1234,
                /* port_loc_map */            portLocMap,
                /* mac_port_map */            macPortMap,
                /* flowExpireMillis */        300*1000,
                /* idleFlowExpireMillis */    60*1000,
                /* publicIp */                publicIP,
                /* macPortTimeoutMillis */    40*1000,
                /* ovsdb */                   ovsdb,
                /* reactor */                 reactor,
                /* externalIdKey */           bridgeExtIdKey,
                /* portSemaphore */           portModSemaphore,
                /* connectionSemaphore */     connectionSemaphore);

            // Get a connection to the OF switch.
            listenSock = ServerSocketChannel.open
            listenSock.configureBlocking(false)
            listenSock.socket.bind(new InetSocketAddress(of_port))

            reactor.register(listenSock, SelectionKey.OP_ACCEPT, 
                             TestBridgeControllerOVS.this)

            registerController

            tookTooLong = reactor.schedule(
                              new Runnable() { 
                                  def run { 
                                      log.info("Took too long!")
                                      tooLongFlag = true
                                      reactor.shutdown
                                      portModSemaphore.release(10)
                                  } }, 
                              4000, TimeUnit.MILLISECONDS)
            reactor.doLoop
            log.info("reactor thread exiting")
        } }

        reactorThread.start

        log.info("Leaving initializeTest()")
    }

    @AfterClass def finalizeTest() {
        if (null == ovsdb) {
            lock.release
            return
        }
        try {
            reactor.shutdown
            assertFalse(tooLongFlag)
            assertTrue(ovsdb.hasController(target))
            assertTrue(ovsdb.hasBridge(bridgeId))
            ovsdb.delBridgeOpenflowControllers(bridgeId)
            assertFalse(ovsdb.hasController(target))
            testDelBridge
            assertFalse(ovsdb.hasBridge(bridgeId))
        } finally {
            try {
                ovsdb.close
            } finally {
                lock.release
            }
        }
    }

    def registerController() {
        var cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
        cb.build
        assertTrue(ovsdb.hasController(target))
    }

    def handleEvent(key: SelectionKey) {
        log.info("handleEvent {}", key)

        var sock = listenSock.accept
        if (sock == null) {
            log.info("Couldn't accept connection -- isAcceptable() = {}",
                     key.isAcceptable)
            return
        }
        log.info("accepted connection from {}",
                 sock.socket.getRemoteSocketAddress)
        sock.socket.setTcpNoDelay(true)
        sock.configureBlocking(false)

        var controllerStub = new ControllerStubImpl(sock, reactor, controller)
        var switchKey = reactor.register(sock, SelectionKey.OP_READ,
                                         controllerStub)
        reactor.wakeup
        controllerStub.start
    }

    def addSystemPort(portName : String) {
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.ifMac("00:01:02:03:04:05");
        pb.build
    }

    def addInternalPort(portName : String) {
        ovsdb.addInternalPort(bridgeName, portName).build
    }

    def addTapPort(portName : String) {
        ovsdb.addTapPort(bridgeName, portName).build
    }
}

class TestBridgeControllerOVS {
    // import all the statics.
    import TestBridgeControllerOVS._

    @Test def testConnectionMade() {
        // Ensure that this runs first, by having the other tests block on
        // serializeTestsSemaphore, which this routine .releases.
        log.info("testConnectionMade")
        // Wait for the connection to be established.
        connectionSemaphore.acquire
        // Drain the portModSemaphroe from the ports of the initial connection.
        portModSemaphore.drainPermits
        serializeTestsSemaphore.release
    }

    @Test 
    @Ignore
    def testNewSystemPort() {
        log.info("testNewSystemPort called")
        serializeTestsSemaphore.acquire
        try {
            log.info("testNewSystemPort has semaphore")
            val portName = "sys" + testportName
            // Clear the list of added ports, and make a new port which should
            // trigger an addPort callback.
            controller.addedPorts = List()
            addSystemPort(portName)
            assertTrue(ovsdb.hasPort(portName))
            log.info("Port {} successfully added to ovsdb", portName)
            // TODO: Verify this is a system port.
            portModSemaphore.acquire
            assertEquals(1, controller.addedPorts.size)
            assertEquals(portName, controller.addedPorts(0).getName)
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
            controller.addedPorts = List()
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewSystemPort exiting")
        }
    }

    @Test def testNewInternalPort() {
        log.info("testNewInternalPort")
        serializeTestsSemaphore.acquire
        log.info("testNewInternalPort has semaphore")
        try {
            val portName = "int" + testportName
            // Clear the list of added ports, and make a new port which should
            // trigger an addPort callback.
            controller.addedPorts = List()
            addInternalPort(portName)
            assertTrue(ovsdb.hasPort(portName))
            portModSemaphore.acquire
            assertEquals(1, controller.addedPorts.size)
            assertEquals(portName, controller.addedPorts(0).getName)
            // TODO: Verify this is an internal port.
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewInternalPort exiting")
        }
    }

    @Ignore 
    @Test def testNewTapPort() = {
        log.info("testNewTapPort")
        serializeTestsSemaphore.acquire
        log.info("testNewTapPort has semaphore")
        try {
            val portName = "tap" + testportName
            addTapPort(portName)
            // Clear the list of added ports, and make a new port which should
            // trigger an addPort callback.
            controller.addedPorts = List()
            assertTrue(ovsdb.hasPort(portName))
            portModSemaphore.acquire
            assertEquals(1, controller.addedPorts.size)
            assertEquals(portName, controller.addedPorts(0).getName)
            // TODO: Verify this is a TAP port.
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewTapPort exiting")
        }
    }
}

private class BridgeControllerTester(datapath: Long, switchID: UUID, 
        greKey: Int, portLocMap: PortToIntNwAddrMap, macPortMap: MacPortMap, 
        flowExpireMillis: Long, idleFlowExpireMillis: Long, 
        publicIP: IntIPv4, macPortTimeoutMillis: Long, 
        ovsdb: OpenvSwitchDatabaseConnectionImpl, reactor: SelectLoop, 
        externalIDKey: String, portSemaphore: Semaphore, 
        connectionSemaphore: Semaphore) extends 
                BridgeController(datapath, switchID, greKey, portLocMap, 
                        macPortMap, flowExpireMillis, idleFlowExpireMillis, 
                        publicIP, macPortTimeoutMillis, ovsdb, reactor, 
                        externalIDKey) {
    var addedPorts = List[OFPhysicalPort]()

    override def onConnectionMade() {
        log.info("BridgeControllerTester: onConnectionMade")
        super.onConnectionMade
        connectionSemaphore.release
    }

    override def addPort(portDesc: OFPhysicalPort, portNum: Short) {
        log.info("BridgeControllerTester: addPort")
        super.addPort(portDesc, portNum)
        addedPorts ::= portDesc
        portSemaphore.release
    }
}
