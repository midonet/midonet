/**
 * TestBgpPortService.scala - BgpPortService test using ovs.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.UUID

import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, Before, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.junit.Assume._
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

import com.midokura.midolman.eventloop.MockReactor
import com.midokura.midolman.layer3.MockServiceFlowController
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionBridgeConnector
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._
import com.midokura.midolman.openvswitch.OpenvSwitchException._
import com.midokura.midolman.packets.MAC
import com.midokura.midolman.quagga.{MockBgpConnection, MockZebraServer}
import com.midokura.midolman.Setup
import com.midokura.midolman.state.{AdRouteZkManager, BgpZkManager,
                                    MockDirectory, PortDirectory,
                                    PortZkManager, RouteZkManager,
                                    RouterZkManager, ZkPathManager}
import com.midokura.midolman.state.BgpZkManager.BgpConfig
import com.midokura.midolman.util.{Net, ShortUUID, Sudo}

/**
 * Test for BgpPortService using Open vSwitch database connection.
 */
object TestBgpPortService
extends OpenvSwitchDatabaseConnectionBridgeConnector {
    private final val log =
        LoggerFactory.getLogger(classOf[TestBgpPortService])

    override final val bridgeName = "testbgp"
    override final val bridgeExtIdKey = "midolman-vnet"
    override final val bridgeExtIdValue = "f5451278-fddd-8b9c-d658-b167aa6c00cc"
    override final val bridgeId: Long = 0x15b138e7fa339bbcL

    private final val reactor = new MockReactor
    private final val portServiceExtIdKey = "midolman_port_service"
    private final val portIdExtIdKey = "midolman_port_id"
    private final val portServiceExtId = "bgp"
    private final var portService: BgpPortService = _
    private final var adRouteMgr: AdRouteZkManager = _
    private final var bgpMgr: BgpZkManager = _
    private final var portMgr: PortZkManager = _
    private final var routeMgr: RouteZkManager = _
    private final var routerMgr: RouterZkManager = _

    private final var portName = "midobgp0"
    private final var portId: UUID = _
    private final var portConfig: PortDirectory.MaterializedRouterPortConfig = _
    private final val portNwAddr = "192.168.10.0"
    private final val portNwLength = 30
    private final val portAddr = "192.168.10.2"
    private final val peerAddr = "192.168.10.1"

    private final var bgpConfig: BgpConfig = _
    private final var bgpId: UUID = _

    @AfterClass
    def finalizeTest() { disconnectFromOVSDB }

    @BeforeClass
    def initializeTest() {
        connectToOVSDB
        log.debug("Successfully connected to OVSDB.")

        val dir = new MockDirectory
        val basePath = "/midolman"
        val pathMgr = new ZkPathManager(basePath)
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.createZkDirectoryStructure(dir, basePath)
        portMgr = new PortZkManager(dir, basePath)
        routerMgr = new RouterZkManager(dir, basePath)
        bgpMgr = new BgpZkManager(dir, basePath)

        portService = new BgpPortService(reactor, this.ovsdb,
                                         portIdExtIdKey, portServiceExtIdKey,
                                         portMgr, routeMgr, bgpMgr, adRouteMgr,
                                         new MockZebraServer,
                                         new MockBgpConnection,
                                         new MockServiceFlowController)
        // Create a provider router.
        val routerId = routerMgr.create

        // Create a materialized router port config.
        portConfig = new PortDirectory.MaterializedRouterPortConfig(
            routerId, Net.convertStringAddressToInt(portNwAddr),
            portNwLength, Net.convertStringAddressToInt(portAddr), null,
            Net.convertStringAddressToInt(portNwAddr), portNwLength, null)
        portId = portMgr.create(portConfig)

        // Create a BGP config.
        bgpConfig = new BgpConfig(portId, 65104,
                                  InetAddress.getByName(peerAddr), 12345)
        bgpId = bgpMgr.create(bgpConfig)
    }
}

class TestBgpPortService {
    import TestBgpPortService._

    @Before
    def testSudo() {
        // Check if it can call sudo w/o password.
        var cmdExitValue = Sudo.sudoExec("ip link")
        if (cmdExitValue != 0) {
            log.warn("sudo w/o password is required to run this test.")
        }
        assumeTrue(cmdExitValue == 0)

        cmdExitValue = Sudo.sudoExec("killall -l")
        if (cmdExitValue != 0) {
            log.warn("sudo w/o password is required to run this test.")
        }
        assumeTrue(cmdExitValue == 0)
    }

    def clearBgpResources() {
        for (portName <- ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                        portServiceExtId)) {
            ovsdb.delPort(portName)
        }
        val portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                       portServiceExtId)
        assertEquals(0, portNames.size)
    }

    // Keep this test at the top - it's assuming that it will run first and
    // therefore it can predict the OF port nums.
    @Test
    def testStart() {
        try {
            // Add the remote port.
            val remotePortName = "dummy"
            var portBuilder = ovsdb.addInternalPort(bridgeId, remotePortName)
            portBuilder.externalId(bridgeExtIdKey, portId.toString)
            portBuilder.build
            assertTrue(ovsdb.hasPort(remotePortName))

            // Add the service port.
            portService.addPort(bridgeId, portId)
            var portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                           portServiceExtId)
            assertEquals(1, portNames.size)
            var ports = portService.configurePort(portId)
            var cmdExitValue = Sudo.sudoExec("ip addr add %s/%d dev %s".format(
                Net.convertIntAddressToString(portConfig.portAddr),
                portConfig.nwLength, portName))
            assertTrue(cmdExitValue != 0)
            // Assuming that remote port num is 1, and the service port num
            // is 2.
            portService.start(bridgeId, 2, 1)
            reactor.incrementTime(1, TimeUnit.SECONDS)
            // Check if bgpd is running.
            cmdExitValue = Sudo.sudoExec("killall bgpd")
            assertTrue(cmdExitValue == 0)

            // Calling start again to reconnet bgpd.
            portService.start(bridgeId, 2, 1)
            ovsdb.delPort(remotePortName)
        } finally {
            clearBgpResources
        }
    }

    @Test
    def testGetPorts() {
        var ports = portService.getPorts(portId)
        assertEquals(0, ports.size)
    }

    @Test
    def testAddPort() {
        try {
            portService.addPort(bridgeId, portId)
            var portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                           portServiceExtId)
            assertEquals(1, portNames.size)
            // Add port with MAC addr specified.
            portService.addPort(bridgeId, portId,
                                MAC.fromString("02:dd:55:66:dd:01"))
            portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                       portServiceExtId)
            assertEquals(2, portNames.size)

            // Delete BGP config and add it after calling addPort.
            clearBgpResources
            bgpMgr.delete(bgpId)
            portService.addPort(bridgeId, portId)
            portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                       portServiceExtId)
            assertEquals(0, portNames.size)
            bgpId = bgpMgr.create(bgpConfig)
            portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                       portServiceExtId)
            assertEquals(1, portNames.size)
        } finally {
            clearBgpResources
        }
    }

    @Test
    def testGetRemotePort() {
        try {
            portService.addPort(bridgeId, portId)
            val portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                           portServiceExtId)
            assertEquals(1, portNames.size)
            for (portName <- portNames) {
                val remotePortName = portService.getRemotePort(portName)
                assertEquals(portId, remotePortName)
            }

            clearBgpResources

            // Test adding a port w/ dummy service id.
            var portBuilder = ovsdb.addSystemPort(bridgeId, portName)
            portBuilder.externalId(portServiceExtIdKey, "dummy_service")
            portBuilder.build
            assertTrue(ovsdb.hasPort(portName))
            assertEquals(null, portService.getRemotePort(portName))
            ovsdb.delPort(portName)

            // Test adding a port w/ dummy remote port id.
            portBuilder = ovsdb.addSystemPort(bridgeId, portName)
            portBuilder.externalId(portServiceExtIdKey, portServiceExtId)
            portBuilder.externalId(bridgeExtIdKey, portId.toString)
            portBuilder.build
            assertTrue(ovsdb.hasPort(portName))
            assertEquals(null, portService.getRemotePort(portName))
        } finally {
            ovsdb.delPort(portName)
            clearBgpResources
        }
    }

    @Test
    def testConfigurePort() {
        try {
            portService.addPort(bridgeId, portId)
            var portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                           portServiceExtId)
            assertEquals(1, portNames.size)
            var ports = portService.configurePort(portId)
            // Test whether the expected ip addr is set. If it is the
            // following command should fail.
            var cmdExitValue = Sudo.sudoExec("ip addr add %s/%d dev %s".format(
                Net.convertIntAddressToString(portConfig.portAddr),
                portConfig.nwLength, portName))
            assertTrue(cmdExitValue != 0)
        } finally {
            clearBgpResources
        }
    }

    @Test(expected = classOf[RuntimeException])
    def testClear() {
        // Raise RuntimeException for unimplemented method.
        portService.clear
    }

    @Test(expected = classOf[RuntimeException])
    def testDelPort() {
        // Raise RuntimeException for unimplemented method.
        portService.delPort(portId)
    }

    @Test(expected = classOf[RuntimeException])
    def testStartFail() {
        // Raise RuntimeException for unimplemented method.
        portService.start(bgpId)
    }

    @Test(expected = classOf[RuntimeException])
    def testStop() {
        // Raise RuntimeException for unimplemented method.
        portService.stop(bgpId)
    }
}
