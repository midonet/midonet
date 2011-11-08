/**
 * TestOpenVpnPortService.scala - OpenVpnPortService test using ovs.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice

import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.{ AfterClass, BeforeClass, Ignore, Test }
import org.junit.Assert._
import org.junit.Assume._
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionBridgeConnector
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._
import com.midokura.midolman.openvswitch.OpenvSwitchException._
import com.midokura.midolman.Setup
import com.midokura.midolman.state.MockDirectory
import com.midokura.midolman.state.PortDirectory
import com.midokura.midolman.state.PortZkManager
import com.midokura.midolman.state.RouterZkManager
import com.midokura.midolman.state.VpnZkManager
import com.midokura.midolman.state.VpnZkManager.{VpnConfig, VpnType}
import com.midokura.midolman.state.ZkPathManager
import com.midokura.midolman.util.Net

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

    private final var portName = "midovpn0"
    private final var pubPortName = portName
    private final var priPortName = "midovpn1"
    private final var pubPortId: UUID = _
    private final var priPortId: UUID = _
    private final val pubPortNw = "192.168.20.2"
    private final val pubPortNwLength = 24
    private final val pubPortAddr = "192.168.20.1"
    private final val priPortNw = "192.168.30.2"
    private final val priPortNwLength = 24
    private final val priPortAddr = "192.168.30.1"
    private final val ruleTableId = 7777

    private final var vpnConfig: VpnConfig = _
    private final var vpnId: UUID = _

    @AfterClass
    def finalizeTest() { disconnectFromOVSDB }

    @BeforeClass
    def initializeTest() {
        connectToOVSDB
        log.debug("Successfully connected to OVSDB.")

        val dir = new MockDirectory();
        val basePath = "/midolman";
        val pathMgr = new ZkPathManager(basePath);
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT)
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
            Net.convertStringAddressToInt(pubPortNw), pubPortNwLength, null)
        pubPortId = portMgr.create(pubPortConfig)

        // Create a private port config.
        val priPortConfig = new PortDirectory.MaterializedRouterPortConfig(
            pRouterId, Net.convertStringAddressToInt(priPortNw),
            priPortNwLength, Net.convertStringAddressToInt(priPortAddr), null,
            Net.convertStringAddressToInt(priPortNw), priPortNwLength, null)
        priPortId = portMgr.create(priPortConfig)

        // Create a vpn config.
        vpnConfig = new VpnConfig(pubPortId, priPortId,
                                  VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
    }
}

class TestOpenVpnPortService {
    import TestOpenVpnPortService._

    @Test
    def testClear() {
        log.debug("testClear")
        // Skip tests if a user don't have sudo access w/o password.
        var cmd = Runtime.getRuntime().exec("sudo -n pwd")
        cmd.waitFor
        if (cmd.exitValue != 0) {
            log.warn("sudo w/o password is required to run this test.")
        }
        assumeTrue(cmd.exitValue == 0)

        assertFalse(ovsdb.hasInterface(portName))
        // Add a private tap port with "midolman_port_service=openvpn".
        val pb = ovsdb.addTapPort(bridgeName, portName)
        pb.externalId(portServiceExtIdKey, portServiceExtId)
        pb.build
        assertTrue(ovsdb.hasPort(portName))

        portService.clear
        assertFalse(ovsdb.hasPort(portName))
    }

    @Test(expected = classOf[RuntimeException])
    def testAddPort() {
        log.debug("testAddPort")
        // Skip tests if a user don't have sudo access w/o password.
        var cmd = Runtime.getRuntime().exec("sudo -n pwd")
        cmd.waitFor
        if (cmd.exitValue != 0) {
            log.warn("sudo w/o password is required to run this test.")
        }
        assumeTrue(cmd.exitValue == 0)

        portService.clear
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
                                                   pubPortId.toString);
        assertEquals(1, portNames.size)
        portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                                                   priPortId.toString);
        assertEquals(1, portNames.size)

        // Destroy all ports and readd them.
        portService.clear
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
                                                   pubPortId.toString);
        assertEquals(1, portNames.size)
        portNames = ovsdb.getPortNamesByExternalId(bridgeExtIdKey,
                                                   priPortId.toString);
        assertEquals(1, portNames.size)
        portService.clear

        // Raise RuntimeException for unimplemented method.
        portService.addPort(0, null)
    }

    @Test
    def testConfigurePort() {
        log.debug("testConfigurePort")
        // Skip tests if a user don't have sudo access w/o password.
        var cmd = Runtime.getRuntime().exec("sudo -n pwd")
        cmd.waitFor
        if (cmd.exitValue != 0) {
            log.warn("sudo w/o password is required to run this test.")
        }
        assumeTrue(cmd.exitValue == 0)

        portService.clear
        assertFalse(ovsdb.hasPort(pubPortName))
        portService.addPort(bridgeId, pubPortId, null)
        assertTrue(ovsdb.hasPort(pubPortName))
        var ipCmd = Runtime.getRuntime().exec(
            "sudo ip addr del %s/%d dev %s".format(pubPortNw, pubPortNwLength,
                                                   pubPortName))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue != 0)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip rule del from %s table %d".format(pubPortNw, ruleTableId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue != 0)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip route del table %d".format(ruleTableId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue != 0)

        portService.configurePort(pubPortId, pubPortName)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip addr add %s/%d dev %s".format(pubPortNw, pubPortNwLength,
                                                   pubPortName))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue != 0)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip route add default via %s table %d".format(pubPortAddr,
                                                               ruleTableId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue != 0)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip route del table %d".format(ruleTableId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue == 0)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip rule del from %s table %d".format(pubPortNw, ruleTableId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue == 0)

        portService.clear
    }

    @Test(expected = classOf[RuntimeException])
    def testStart() {
        log.debug("testStart")
        // Skip tests if a user don't have sudo access w/o password.
        var cmd = Runtime.getRuntime().exec("sudo -n pwd")
        cmd.waitFor
        if (cmd.exitValue != 0) {
            log.warn("sudo w/o password is required to run this test.")
        }
        assumeTrue(cmd.exitValue == 0)

        portService.clear
        assertFalse(ovsdb.hasPort(pubPortName))
        portService.addPort(bridgeId, pubPortId, null)
        assertTrue(ovsdb.hasPort(pubPortName))
        assertFalse(ovsdb.hasPort(priPortName))
        portService.addPort(bridgeId, priPortId, null)
        assertTrue(ovsdb.hasPort(priPortName))
        portService.configurePort(pubPortId, pubPortName)
        var ipCmd = Runtime.getRuntime().exec(
            "sudo killall %s".format(portServiceExtId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue != 0)

        portService.start(vpnId)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip route del table %d".format(ruleTableId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue == 0)
        ipCmd = Runtime.getRuntime().exec(
            "sudo ip rule del from %s table %d".format(pubPortNw, ruleTableId))
        ipCmd.waitFor
        assertTrue(ipCmd.exitValue == 0)

        portService.clear
        portService.start(0, 0, 0)
    }

    @Test(expected = classOf[RuntimeException])
    def tesSetController() {
        log.debug("testSetController")
        portService.setController(null)
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
