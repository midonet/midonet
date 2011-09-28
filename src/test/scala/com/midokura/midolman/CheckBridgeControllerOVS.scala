/**
 * CheckBridgeControllerOVS.scala - Test BridgeController's interactions with
 *                                  Open vSwitch.
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
import com.midokura.midolman.state.{MacPortMap, MockDirectory, 
                                    PortToIntNwAddrMap}

import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.slf4j.LoggerFactory

import java.io.{File, RandomAccessFile}
import java.lang.Runnable
import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.{FileLock, SelectionKey, ServerSocketChannel}
import java.util.concurrent.{Executors, TimeUnit, ScheduledFuture, Semaphore}
import java.util.{Date, UUID}

/**
 * Test the BridgeController's interaction with Open vSwitch.
 */
object CheckBridgeControllerOVS extends SelectListener {
    // Share a common OVSDB connection because using two breaks.
    import TestShareOneOpenvSwitchDatabaseConnection._

    // All the "static" variables and methods.
    final val log = LoggerFactory.getLogger(classOf[CheckBridgeControllerOVS])
    private final val testportName = "testbrport"
    private final val publicIP = /* 192.168.1.50 */
        InetAddress.getByAddress(
            Array(192.toByte, 168.toByte, 1.toByte, 50.toByte))
    private final var controller: BridgeController = _
    private var zkDir = new MockDirectory
    private final val zkRoot = "/zk_root"
    private final val of_port = 6633
    private final var listenSock: ServerSocketChannel = _
    private final var reactor: SelectLoop = _
    private final val target = "tcp:127.0.0.1"
    private final var tookTooLong: ScheduledFuture[_] = _
    private final var reactorThread: Thread = _
    private final var semLock = new Semaphore(1)
    @volatile private var tooLongFlag = false

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

        semLock.acquire
        reactorThread = new Thread() { override def run() = {
            log.info("reactorThread starting")
            reactor = new SelectLoop(Executors.newScheduledThreadPool(1))

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
                /* semLock */                 semLock);

            // Get a connection to the OF switch.
            listenSock = ServerSocketChannel.open
            listenSock.configureBlocking(false)
            listenSock.socket.bind(new InetSocketAddress(of_port))

            reactor.register(listenSock, SelectionKey.OP_ACCEPT, 
                             CheckBridgeControllerOVS.this)

            registerController

            tookTooLong = reactor.schedule(
                              new Runnable() { 
                                  def run = { 
                                      log.info("Took too long!")
                                      tooLongFlag = true
                                      reactor.shutdown
                                      semLock.release
                                  } }, 
                              4000, TimeUnit.MILLISECONDS)
            reactor.doLoop
        } }

        reactorThread.start

        log.info("Leaving initializeTest()")
    }

    @AfterClass def finalizeTest() {
        reactor.shutdown
        assertFalse(tooLongFlag)
        assertTrue(ovsdb.hasController(target))
        ovsdb.delBridgeOpenflowControllers(bridgeId)
        assertFalse(ovsdb.hasController(target))
    }

    def registerController() = {
        var cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
        cb.build
        assertTrue(ovsdb.hasController(target))
    }

    def handleEvent(key: SelectionKey) = {
        log.info("handleEvent {}", key)

        var sock = listenSock.accept
        log.info("accepted connection from {}", 
                 sock.socket.getRemoteSocketAddress)
        sock.socket.setTcpNoDelay(true)
        sock.configureBlocking(false)
        
        var controllerStub = new ControllerStubImpl(sock, reactor, controller)
        var switchKey = reactor.registerBlocking(sock, SelectionKey.OP_READ,
                                                 controllerStub)
        switchKey.interestOps(SelectionKey.OP_READ)
        reactor.wakeup
        controllerStub.start
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

    @Test def testConnectionMade() = {
        log.info("testConnectionMade")
        semLock.acquire
        log.info("have semaphore")
        reactor.shutdown
    }
}

private class BridgeControllerTester(datapath: Long, switchID: UUID, 
        greKey: Int, portLocMap: PortToIntNwAddrMap, macPortMap: MacPortMap, 
        flowExpireMillis: Long, idleFlowExpireMillis: Long, 
        publicIP: InetAddress, macPortTimeoutMillis: Long, 
        ovsdb: OpenvSwitchDatabaseConnectionImpl, reactor: SelectLoop, 
        externalIDKey: String, semLock: Semaphore) extends 
                BridgeController(datapath, switchID, greKey, portLocMap, 
                        macPortMap, flowExpireMillis, idleFlowExpireMillis, 
                        publicIP, macPortTimeoutMillis, ovsdb, reactor, 
                        externalIDKey) {
    override def onConnectionMade() = {
        log.info("BridgeControllerTester: onConnectionMade")
        super.onConnectionMade
        semLock.release
    }
}
