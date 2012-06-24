/**
 * TestOpenVpnPortService.scala - OpenVpnPortService test using ovs.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice

import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.slf4j.LoggerFactory


import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionBridgeConnector
import com.midokura.midolman.state.MockDirectory
import com.midokura.midolman.state.PortDirectory
import com.midokura.midolman.state.PortZkManager
import com.midokura.midolman.state.RouterZkManager
import com.midokura.midolman.state.VpnZkManager
import com.midokura.midolman.state.VpnZkManager.{VpnConfig, VpnType}
import com.midokura.midolman.state.ZkPathManager
import com.midokura.midolman.util.Net
import com.midokura.midolman.Setup
import com.midokura.midolman.TestHelpers._
import java.util

/**
 * Test for OpenVpnPortService using Open vSwitch database connection.
 */
object TestOpenVpnPortService
    extends OpenvSwitchDatabaseConnectionBridgeConnector {

    private final val log =
        LoggerFactory.getLogger(classOf[TestOpenVpnPortService])

    override final val bridgeName = "testvpn"
    override final val bridgeExtIdKey = "midolman-vnet"
    override final val bridgeExtIdValue = "f5451278-fddd-8b9c-d658-b167aa6c00cc"
    override final val bridgeId: Long = 0x15b138e7fa339bbcL

    private final val portServiceExtIdKey = "midolman_port_service"
    private final val portServiceExtId = "openvpn"
    private final var portService: OpenVpnPortService = _
    private final var portMgr: PortZkManager = _
    private final var routerMgr: RouterZkManager = _
    private final var vpnMgr: VpnZkManager = _

    private final val portName = "midovpn0"
    private final val pubPortName = portName
    private final val priPortName = "midovpn1"

    private final var pubPortId: util.UUID = _
    private final var priPortId: util.UUID = _
    private final val pubPortNw = "192.168.20.2"
    private final val pubPortNwLength = 24
    private final val pubPortAddr = "192.168.20.1"
    private final val priPortNw = "192.168.30.2"
    private final val priPortNwLength = 24
    private final val priPortAddr = "192.168.30.1"

    private final var vpnConfig: VpnConfig = _
    private final var vpnId: util.UUID = _
    private final var vpnConfig2: VpnConfig = _
    private final var vpnId2: util.UUID = _

    private def getRuleTableId(portId: util.UUID): Int = {
        portId.hashCode() & 0xfff
    }

    @AfterClass
    def finalizeTest() {
        disconnectFromOVSDB
    }

    @BeforeClass
    def initializeTest() {
        connectToOVSDB
        log.debug("Successfully connected to OVSDB.")

        val dir = new MockDirectory()
        val basePath = "/midolman"
        val pathMgr = new ZkPathManager(basePath)
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.createZkDirectoryStructure(dir, basePath)
        portMgr = new PortZkManager(dir, basePath)
        routerMgr = new RouterZkManager(dir, basePath)
        vpnMgr = new VpnZkManager(dir, basePath)

        portService = new OpenVpnPortService(this.ovsdb, bridgeExtIdKey,
            portServiceExtIdKey, portMgr,
            vpnMgr)
        // Create a provider router and a tenant router.
        val pRouterId = routerMgr.create
        val tRouterId = routerMgr.create

        // Create a public port config.
        val pubPortConfig = new PortDirectory.MaterializedRouterPortConfig(
            pRouterId, Net.convertStringAddressToInt(pubPortNw),
            pubPortNwLength, Net.convertStringAddressToInt(pubPortAddr), null,
            null, Net.convertStringAddressToInt(pubPortNw), pubPortNwLength, null)
        pubPortId = portMgr.create(pubPortConfig)

        // Create a private port config.
        val priPortConfig = new PortDirectory.MaterializedRouterPortConfig(
            pRouterId, Net.convertStringAddressToInt(priPortNw),
            priPortNwLength, Net.convertStringAddressToInt(priPortAddr), null,
            null, Net.convertStringAddressToInt(priPortNw), priPortNwLength, null)
        priPortId = portMgr.create(priPortConfig)

        // Create an UDP server vpn config.
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
            VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        // Create a TCP server vpn config.
        vpnConfig2 = new VpnConfig(pubPortId, priPortId, null,
            VpnType.OPENVPN_TCP_SERVER, 1154)
        vpnId2 = vpnMgr.create(vpnConfig2)
    }
}

class TestOpenVpnPortService {
    import TestOpenVpnPortService._

    @Test
    def testClear() {
        portService.synchronized {
            log.debug("testClear")
            // Skip tests if a user don't have sudo access w/o password.
            assumeSudoAccess("ip link")

            assertFalse(ovsdb.hasInterface(portName))
            // Add a private tap port with "midolman_port_service=openvpn".
            val pb = ovsdb.addTapPort(bridgeName, portName)
            pb.externalId(portServiceExtIdKey, portServiceExtId)
            pb.build()
            assertTrue(ovsdb.hasPort(portName))

            portService.clear()
            assertFalse(ovsdb.hasPort(portName))
        }
    }

    @Test
    def testAddPort() {
        portService.synchronized {
            log.debug("testAddPort")
            // Skip tests if a user don't have sudo access w/o password.
            assumeSudoAccess("ip link")

            portService.clear()
            assertFalse(ovsdb.hasPort(pubPortName))
            portService.addPort(bridgeId, pubPortId, null)
            assertTrue(ovsdb.hasPort(pubPortName))
            assertFalse(ovsdb.hasPort(priPortName))
            portService.addPort(bridgeId, priPortId, null)
            assertTrue(ovsdb.hasPort(priPortName))

            var portNames = ovsdb.getPortNamesByExternalId(
                portServiceExtIdKey, portServiceExtId)
            assertEquals(2, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                pubPortId.toString)
            assertEquals(1, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                priPortId.toString)
            assertEquals(1, portNames.size)

            // Destroy all ports and readd them.
            portService.clear()
            assertFalse(ovsdb.hasPort(pubPortName))
            portService.addPort(bridgeId, pubPortId, null)
            assertTrue(ovsdb.hasPort(pubPortName))
            assertFalse(ovsdb.hasPort(priPortName))
            portService.addPort(bridgeId, priPortId, null)
            assertTrue(ovsdb.hasPort(priPortName))

            portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                portServiceExtId)
            assertEquals(2, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                pubPortId.toString)
            assertEquals(1, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                priPortId.toString)
            assertEquals(1, portNames.size)
            portService.clear()
        }
    }

    @Test(expected = classOf[RuntimeException])
    def testAddPortFail() {
        // Raise RuntimeException for unimplemented method.
        portService.addPort(0, null)
    }

    @Test def testConfigurePort() {
        portService.synchronized {
            log.debug("testConfigurePort")
            // Skip tests if a user don't have sudo access w/o password.
            assumeSudoAccess("ip link")

            portService.clear()
            assertFalse(ovsdb.hasPort(pubPortName))
            portService.addPort(bridgeId, pubPortId, null)
            assertTrue(ovsdb.hasPort(pubPortName))

            // Check there is no left over addrs, rules or routes are in the
            // running environment.
            assertCommandFails(
                "ip addr del %s/%d dev %s".format(pubPortNw, pubPortNwLength, pubPortName))

            val ruleTableId = getRuleTableId(pubPortId)
            assertCommandFails(
                "ip rule del from %s table %d".format(pubPortNw, ruleTableId))

            assertCommandFails(
                "ip route del table %d".format(ruleTableId))

            portService.configurePort(pubPortId, pubPortName)
            assertCommandFails(
                "ip addr add %s/%d dev %s".format(pubPortNw, pubPortNwLength, pubPortName))

            assertCommandFails(
                "ip route add default via %s table %d".format(pubPortAddr, ruleTableId))

            // Check expected rule is added. The command should succeed if there is.
            assertCommandSucceeds(
                "ip rule del from %s table %d".format(pubPortNw, ruleTableId))

            // Check expected route is added to the table. The command should
            // succeed if it exists.
            assertCommandSucceeds(
                "ip route del table %d".format(ruleTableId))

            portService.clear()
        }
    }

    @Test
    def testDelPort() {
        portService.synchronized {
            log.debug("testDelPort")
            // Skip tests if a user don't have sudo access w/o password.
            assumeSudoAccess("ip link")

            portService.clear()
            assertFalse(ovsdb.hasPort(pubPortName))
            portService.addPort(bridgeId, pubPortId, null)
            assertTrue(ovsdb.hasPort(pubPortName))
            assertFalse(ovsdb.hasPort(priPortName))
            portService.addPort(bridgeId, priPortId, null)
            assertTrue(ovsdb.hasPort(priPortName))

            var portNames = ovsdb.getPortNamesByExternalId(
                portServiceExtIdKey, portServiceExtId)
            assertEquals(2, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                pubPortId.toString)
            assertEquals(1, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                priPortId.toString)
            assertEquals(1, portNames.size)

            // Delete ports.
            portService.delPort(pubPortId)
            assertFalse(ovsdb.hasPort(pubPortName))
            portService.delPort(priPortId)
            assertFalse(ovsdb.hasPort(priPortName))

            portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                portServiceExtId)
            assertEquals(0, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                pubPortId.toString)
            assertEquals(0, portNames.size)
            portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                priPortId.toString)
            assertEquals(0, portNames.size)

            portService.clear()
        }
    }

    @Test
    def testStart() {
        portService.synchronized {
            try {
                log.debug("testStart")
                // Skip tests if a user don't have sudo access w/o password.
                assumeSudoAccess("ip link")

                portService.clear()
                assertFalse(ovsdb.hasPort(pubPortName))
                portService.addPort(bridgeId, pubPortId, null)
                assertTrue(ovsdb.hasPort(pubPortName))
                assertFalse(ovsdb.hasPort(priPortName))
                portService.addPort(bridgeId, priPortId, null)
                assertTrue(ovsdb.hasPort(priPortName))
                portService.configurePort(pubPortId, pubPortName)

                assertCommandFails(
                    "killall %s".format(portServiceExtId))

                portService.start(vpnId)
                val ruleTableId = getRuleTableId(pubPortId)
                // Check expected rule is added. The command should succeed if
                // there is.
                assertCommandSucceeds(
                    "ip rule del from %s table %d".format(pubPortNw, ruleTableId))

                // Check expected route is added to the table. The command should
                // succeed if it exists.
                assertCommandSucceeds(
                    "ip route del table %d".format(ruleTableId))

                assertCommandSucceeds(
                    "killall %s".format(portServiceExtId))

                portService.clear()
            } catch {
                case e: java.io.IOException =>
                    log.error("IOException in testStart: ", e)
            }
        }
    }

    @Test
    def testStartTcpServer() {
        portService.synchronized {
            try {
                log.debug("testStartTcpServer")
                // Skip tests if a user don't have sudo access w/o password.
                assumeSudoAccess("ip link")

                portService.clear()
                assertFalse(ovsdb.hasPort(pubPortName))
                portService.addPort(bridgeId, pubPortId, null)
                assertTrue(ovsdb.hasPort(pubPortName))
                assertFalse(ovsdb.hasPort(priPortName))
                portService.addPort(bridgeId, priPortId, null)
                assertTrue(ovsdb.hasPort(priPortName))
                portService.configurePort(pubPortId, pubPortName)

                assertCommandFails(
                    "killall %s".format(portServiceExtId))

                portService.start(vpnId2)
                val ruleTableId = getRuleTableId(pubPortId)
                // Check expected rule is added. The command should succeed if
                // there is.
                assertCommandSucceeds(
                    "ip rule del from %s table %d".format(pubPortNw, ruleTableId))

                // Check expected route is added to the table. The command should
                // succeed if it exists.
                assertCommandSucceeds(
                    "ip route del table %d".format(ruleTableId))

                assertCommandSucceeds(
                    "killall %s".format(portServiceExtId))

                portService.clear()
            } catch {
                case e: java.io.IOException =>
                    log.error("IOException in testStart: ", e)
            }
        }
    }

    @Test(expected = classOf[RuntimeException])
    def testStartFail() {
        // Raise RuntimeException for unimplemented method.
        portService.start(0, 0, 0)
    }

    @Test
    def testStop() {
        portService.synchronized {
            try {
                log.debug("testStop")
                // Skip tests if a user don't have sudo access w/o password.
                assumeSudoAccess("ip link")

                portService.clear()
                assertFalse(ovsdb.hasPort(pubPortName))
                portService.addPort(bridgeId, pubPortId, null)
                assertTrue(ovsdb.hasPort(pubPortName))
                assertFalse(ovsdb.hasPort(priPortName))
                portService.addPort(bridgeId, priPortId, null)
                assertTrue(ovsdb.hasPort(priPortName))
                portService.configurePort(pubPortId, pubPortName)

                assertCommandFails("killall %s".format(portServiceExtId))

                portService.start(vpnId)

                val ruleTableId = getRuleTableId(pubPortId)
                // Check expected rule is added. The command should succeed if
                // there is.
                assertCommandSucceeds(
                    "ip rule del from %s table %d".format(pubPortNw, ruleTableId))

                // Check expected route is added to the table. The command should
                // succeed if it exists.
                assertCommandSucceeds(
                    "ip route del table %d".format(ruleTableId))

                portService.stop(vpnId)

                Thread.sleep(3000)
                // Check openvpn process doesn't exist.
                // Waiting 1sec to helps to destroy the process.
                assertCommandFails("killall %s".format(portServiceExtId))

                portService.clear()
            } catch {
                case e: java.io.IOException =>
                    log.error("IOException in testStart: ", e)
            }
        }
    }

    @Test(expected = classOf[RuntimeException])
    def testGetPorts() {
        log.debug("testGetPorts")
        portService.getPorts(null)
    }

    @Test(expected = classOf[RuntimeException])
    def testGetRemotePort() {
        log.debug("testGetRemotePort")
        portService.getRemotePort(null)
    }
}
