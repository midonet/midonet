/**
 * TestVpnPortAgent.scala - VpnPortAgent test.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice

import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.{ AfterClass, BeforeClass, Ignore, Test }
import org.junit.Assert._
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

import com.midokura.midolman.eventloop.MockReactor
import com.midokura.midolman.Setup
import com.midokura.midolman.state.{MockDirectory, PortDirectory,
                                    PortZkManager, RouterZkManager,
                                    StateAccessException, VpnZkManager,
                                    ZkPathManager}
import com.midokura.midolman.state.VpnZkManager.{VpnConfig, VpnType}
import com.midokura.midolman.util.Net


/**
 * Test for VpnPortAgent.
 */
object TestVpnPortAgent {
    private final val log =
        LoggerFactory.getLogger(classOf[TestOpenVpnPortService])

    private final val bridgeId: Long = 0x15b138e7fa339bbcL
    private final val sessionId: Long = 0x123456789abcdefL
    private final var vpnAgent: VpnPortAgent = _
    private final var portService: PortService = _
    private final var dir: MockDirectory = _
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

    private final var vpnConfig: VpnConfig = _
    private final var vpnId: UUID = _

    @AfterClass
    def finalizeTest() {
    }

    @BeforeClass
    def initializeTest() {
        val dir = new MockDirectory();
        val basePath = "/midolman";
        val pathMgr = new ZkPathManager(basePath);
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT)
        Setup.createZkDirectoryStructure(dir, basePath)
        portMgr = new PortZkManager(dir, basePath)
        routerMgr = new RouterZkManager(dir, basePath)
        vpnMgr = new VpnZkManager(dir, basePath)

        // TODO(yoshi): create mock port service.

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

        portService = new MockPortService(portMgr, vpnMgr)
        vpnAgent = new VpnPortAgent(sessionId, bridgeId, vpnMgr,
                                    VpnType.OPENVPN_SERVER, portService)
    }
}

class TestVpnPortAgent {
    import TestVpnPortAgent._

    @Test
    def testStart() {
        log.debug("testStart")
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
                                  VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        vpnAgent.start
        assertEquals(1, vpnAgent.getVpn.size)
        assertEquals(2, vpnAgent.getPorts.size)
        vpnAgent.stop
        assertEquals(0, vpnAgent.getVpn.size)
        assertEquals(0, vpnAgent.getPorts.size)
        vpnMgr.delete(vpnId)
    }

    @Test
    def testStartLock() {
        log.debug("testStartLock")
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
                                  VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        vpnMgr.lock(vpnId, sessionId)
        vpnAgent.start
        vpnMgr.unlock(vpnId)
        assertEquals(1, vpnAgent.getVpn.size)
        assertEquals(2, vpnAgent.getPorts.size)
        vpnAgent.stop
        assertEquals(0, vpnAgent.getVpn.size)
        assertEquals(0, vpnAgent.getPorts.size)
        vpnMgr.delete(vpnId)
    }

    @Test
    def testStartAfterDelete() {
        log.debug("testStartAfterDelete")
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
                                  VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        vpnMgr.lock(vpnId, sessionId)
        vpnAgent.start
        vpnMgr.delete(vpnId)
        assertEquals(0, vpnAgent.getVpn.size)
        assertEquals(0, vpnAgent.getPorts.size)
        vpnAgent.stop
        assertEquals(0, vpnAgent.getVpn.size)
        assertEquals(0, vpnAgent.getPorts.size)
    }

    @Test(expected = classOf[StateAccessException])
    def testLockAfterStart() {
        log.debug("testLockAfterStart")
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
                                  VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        vpnAgent.start
        assertEquals(1, vpnAgent.getVpn.size)
        assertEquals(2, vpnAgent.getPorts.size)
        try {
            vpnMgr.lock(vpnId, sessionId)
        } finally {
            vpnAgent.stop
            vpnMgr.delete(vpnId)
            assertEquals(0, vpnAgent.getVpn.size)
            assertEquals(0, vpnAgent.getPorts.size)
        }
    }

    @Test
    def testDelete() {
        log.debug("testDelete")
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
                                  VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        vpnAgent.start
        assertEquals(1, vpnAgent.getVpn.size)
        assertEquals(2, vpnAgent.getPorts.size)
        vpnMgr.delete(vpnId)
        assertEquals(0, vpnAgent.getVpn.size)
        assertEquals(0, vpnAgent.getPorts.size)
        vpnAgent.stop
    }

    @Test
    def testDeleteAfterUnlock() {
        log.debug("testDeleteAfterUnlock")
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
                                  VpnType.OPENVPN_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        vpnMgr.lock(vpnId, sessionId)
        vpnAgent.start
        vpnMgr.unlock(vpnId)
        assertEquals(1, vpnAgent.getVpn.size)
        assertEquals(2, vpnAgent.getPorts.size)
        vpnMgr.delete(vpnId)
        assertEquals(0, vpnAgent.getVpn.size)
        assertEquals(0, vpnAgent.getPorts.size)
        vpnAgent.stop
    }

    @Test
    def testSetPortService() {
        log.debug("testSetPortService")
        vpnAgent.setPortService(VpnType.OPENVPN_TCP_SERVER, portService)
        vpnConfig = new VpnConfig(pubPortId, priPortId, null,
                                  VpnType.OPENVPN_TCP_SERVER, 1154)
        vpnId = vpnMgr.create(vpnConfig)
        vpnAgent.start
        assertEquals(1, vpnAgent.getVpn.size)
        assertEquals(2, vpnAgent.getPorts.size)
        vpnAgent.stop
        assertEquals(0, vpnAgent.getVpn.size)
        assertEquals(0, vpnAgent.getPorts.size)
        vpnMgr.delete(vpnId)
    }
}
