/**
 * TestBridgeZkManager.scala - BridgeZkManager test.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.state

import java.util.UUID

import org.apache.zookeeper.CreateMode
import org.junit.{ AfterClass, BeforeClass, Ignore, Test }
import org.junit.Assert._
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

import com.midokura.midolman.Setup
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig
import com.midokura.midolman.util.Net


/**
 * Test for BridgeZkManager.
 */
object TestBridgeZkManager {
    private final val log =
        LoggerFactory.getLogger(classOf[TestBridgeZkManager])

    private final var bridgeMgr: BridgeZkManager = _
    private final var bridgeConfig: BridgeConfig = _
    private final var bridgeId: UUID = _

    // TODO: Do we even need to have this defined, if it's empty?
    @AfterClass
    def finalizeTest() {
    }

    @BeforeClass
    def initializeTest() {
        val dir = new MockDirectory();
        val basePath = "/midolman";
        val pathMgr = new ZkPathManager(basePath);
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.createZkDirectoryStructure(dir, basePath)
        bridgeMgr = new BridgeZkManager(dir, basePath)
    }
}

class TestBridgeZkManager {
    import TestBridgeZkManager._

    @Test
    def testCreateBridges() {
        // Create three bridges, check that their identifying GRE keys (tunnel
        // IDs) are 1, 2, 3, in order.
        bridgeId = bridgeMgr.create(new BridgeConfig())
        bridgeConfig = bridgeMgr.get(bridgeId)
        assertEquals(1, bridgeConfig.greKey)
        bridgeId = bridgeMgr.create(new BridgeConfig())
        bridgeConfig = bridgeMgr.get(bridgeId)
        assertEquals(2, bridgeConfig.greKey)
        bridgeId = bridgeMgr.create(new BridgeConfig())
        bridgeConfig = bridgeMgr.get(bridgeId)
        assertEquals(3, bridgeConfig.greKey)
    }
}
