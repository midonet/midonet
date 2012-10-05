/**
 * TestVtyConnection.scala - Tests for Quagga VTY connection management classes.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.quagga

import java.net.InetAddress
import java.util.UUID

import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, Before, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.junit.Assume._
import org.slf4j.LoggerFactory

import com.midokura.midolman.Setup
import com.midokura.midolman.state.zkManagers._
import com.midokura.midolman.util.{Net, Sudo}
import com.midokura.midolman.state.zkManagers.AdRouteZkManager.AdRouteConfig
import com.midokura.midolman.state.{ZkPathManager, MockDirectory, PortDirectory}
import com.midokura.midonet.cluster.data.BGP
import com.midokura.packets.IntIPv4


object TestBgpVtyConnection {
    private final val log =
        LoggerFactory.getLogger(classOf[TestBgpVtyConnection])

    private final var vtyConn: BgpVtyConnection = _
    private final var bgpd: Process = _
    private final val bgpdPort = 2605
    private final val bgpdPassword = "zebra"
    private final var checkSudo = false
    private final var adRouteMgr: AdRouteZkManager = _
    private final var bgpMgr: BgpZkManager = _
    private final var portMgr: PortZkManager = _
    private final var routerMgr: RouterZkManager = _

    private final var portId: UUID = _
    private final var portConfig: PortDirectory.MaterializedRouterPortConfig = _
    private final val portNwAddr = "192.168.10.0"
    private final val portNwLength = 30
    private final val portAddr = "192.168.10.2"
    private final val peerAddr = "192.168.10.1"
    private final val localAs = 65104
    private final val peerAs = 12345

    private final var bgpConfig: BGP = _
    private final var bgpId: UUID = _
    private final var adRouteConfig: AdRouteConfig = _
    private final var adRouteId: UUID = _

    @AfterClass
    def finalizeTest() { Sudo.sudoExec("killall bgpd") }

    @BeforeClass
    def initializeTest() {
        val dir = new MockDirectory
        val basePath = "/midolman"
        val pathMgr = new ZkPathManager(basePath)
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.createZkDirectoryStructure(dir, basePath)
        portMgr = new PortZkManager(dir, basePath)
        routerMgr = new RouterZkManager(dir, basePath)
        bgpMgr = new BgpZkManager(dir, basePath)
        adRouteMgr = new AdRouteZkManager(dir, basePath)

        // Create a provider router.
        val routerId = routerMgr.create

        // Create a materialized router port config.
        portConfig = new PortDirectory.MaterializedRouterPortConfig(
            routerId, Net.convertStringAddressToInt(portNwAddr),
            portNwLength, Net.convertStringAddressToInt(portAddr), null, null,
            null)
        portId = portMgr.create(portConfig)

        // Create a BGP config.
        bgpConfig = new BGP()
            .setPortId(portId).setLocalAS(localAs).setPeerAS(peerAs)
            .setPeerAddr(IntIPv4.fromString(peerAddr))
        bgpId = bgpMgr.create(bgpConfig)
        // Create advertising routes
        adRouteConfig = new AdRouteConfig(
            bgpId, InetAddress.getByName("192.168.20.0"), 24)
        adRouteId = adRouteMgr.create(adRouteConfig)

        vtyConn = new BgpVtyConnection("localhost", bgpdPort, bgpdPassword)
        // Check if it can run bgpd w/o password. It will raise
        // IllegalThreadStateException if the bgpd gets launched correctly.
        try {
            bgpd = Runtime.getRuntime().exec("sudo -n /usr/lib/quagga/bgpd")
            // Wait 1sec before checking the return value. We can't use
            // waitFor because bgpd will then block this test.
            Thread.sleep(1000)
            bgpd.exitValue
        } catch {
            case e: IllegalThreadStateException => { checkSudo = true }
        }
    }
}

@Ignore
class TestBgpVtyConnection {
    import TestBgpVtyConnection._

    @Before
    def testSudo() {
        if (!checkSudo) {
            log.warn("sudo w/o password is required to run this test.")
        }
        assumeTrue(checkSudo)
    }

    @Test
    def testCreate() {
        log.debug("testCreate")

        try {
            // getAs should return 0 if no BGP config is set.
            assertEquals(0, vtyConn.getAs)
            //vtyConn.create(InetAddress.getByName(portAddr), bgpId, bgpConfig)
            assertEquals(localAs, vtyConn.getAs)
        } finally {
            vtyConn.deleteAs(localAs)
        }
    }

    @Test
    def testDeleteAfterCreate() {
        log.debug("testDeleteAfterCreate")

        try {
            // getAs should return 0 if no BGP config is set.
            assertEquals(0, vtyConn.getAs)
            //vtyConn.create(InetAddress.getByName(portAddr), bgpId, bgpConfig)
            assertEquals(localAs, vtyConn.getAs)
            // Delete the BGP config
            bgpMgr.delete(bgpId)
            assertEquals(0, vtyConn.getAs)
            // Recreate the BGP and advertising route config
            bgpId = bgpMgr.create(bgpConfig)
            adRouteConfig.bgpId = bgpId
            adRouteId = adRouteMgr.create(adRouteConfig)
            // We don't support dynamically adding new BGP config because
            // when you use Quagga, you usually edit the config file and
            // restart the daemon.
            assertEquals(0, vtyConn.getAs)
        } finally {
            vtyConn.deleteAs(localAs)
        }
    }

    @Test
    def testAppendAdRoutes() {
        log.debug("testAppendAdRoutes")

        try {
            // getAs should return 0 if no BGP config is set.
            assertEquals(0, vtyConn.getAs)
            //vtyConn.create(InetAddress.getByName(portAddr), bgpId, bgpConfig)
            assertEquals(localAs, vtyConn.getAs)

            // Create advertising routes.
            val nwAddr = "10.8.8.0"
            val prefixLen:Byte = 24
            var newAdRoute = new AdRouteConfig(
                bgpId, InetAddress.getByName(nwAddr), prefixLen)
            val newAdRouteId = adRouteMgr.create(newAdRoute)
            val networks = vtyConn.getNetwork
            assertEquals(2, networks.size)
            assertTrue(networks.contains("%s/%d".format(nwAddr, prefixLen)))
            adRouteMgr.delete(newAdRouteId)
            // Need to wait until bgpd's config changes.
            Thread.sleep(1000)
            assertEquals(1, vtyConn.getNetwork.size)
        } finally {
            vtyConn.deleteAs(localAs)
        }
    }

    @Test
    def testUpdateAdRoutes() {
        log.debug("testUpdateAdRoutes")

        try {
            // getAs should return 0 if no BGP config is set.
            assertEquals(0, vtyConn.getAs)
            assertEquals(0, vtyConn.getNetwork.size)
            //vtyConn.create(InetAddress.getByName(portAddr), bgpId, bgpConfig)
            assertEquals(localAs, vtyConn.getAs)
            // Update advertising route
            val nwAddr = "192.168.30.0"
            val prefixLen:Byte = 24
            var updateAdRoute = new AdRouteConfig(
                bgpId, InetAddress.getByName(nwAddr), prefixLen)
            adRouteMgr.update(adRouteId, updateAdRoute)
            // Need to wait until bgpd's config changes.
            Thread.sleep(1000)
            val networks = vtyConn.getNetwork
            assertEquals(1, networks.size)
            // bgpd won't show /24 in this case.
            assertTrue(networks.contains(nwAddr))
        } finally {
            vtyConn.deleteAs(localAs)
        }
    }
}
