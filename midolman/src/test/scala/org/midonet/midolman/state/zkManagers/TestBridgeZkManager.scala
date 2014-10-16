/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.state.zkManagers

/**
 * TestBridgeZkManager.scala - BridgeZkManager test.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

import java.util.UUID

import org.apache.zookeeper.CreateMode
import org.junit.Assert._
import org.slf4j.LoggerFactory

import org.midonet.midolman.Setup
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import org.midonet.midolman.state.{PathBuilder, ZkManager, MockDirectory}
import org.midonet.midolman.version.VersionComparator

import org.junit.{Test, BeforeClass, AfterClass}
import org.midonet.midolman.version.serialization.JsonVersionZkSerializer
import org.midonet.midolman.state.ZkSystemDataProvider


/**
 * Test for BridgeZkManager.
 */
object TestBridgeZkManager {
    private val log = LoggerFactory.getLogger(classOf[TestBridgeZkManager])

    private final var bridgeMgr: BridgeZkManager = _
    private final var bridgeConfig: BridgeConfig = _
    private final var bridgeId: UUID = _

    // TODO: Do we even need to have this defined, if it's empty?
    @AfterClass
    def finalizeTest() {
    }

  @BeforeClass
  def initializeTest() {
      val basePath = "/midolman";
      val pathMgr = new PathBuilder(basePath);
      val dir = new MockDirectory();
      val zk = new ZkManager(dir, basePath);
      val dataProvider = new ZkSystemDataProvider(zk, pathMgr,
          new VersionComparator());
      val serializer = new JsonVersionZkSerializer(dataProvider,
          new VersionComparator());
      dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
      Setup.ensureZkDirectoryStructureExists(dir, basePath)
      bridgeMgr = new BridgeZkManager(new ZkManager(dir, basePath),
                                      pathMgr, serializer)
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
        assertEquals(1, bridgeConfig.tunnelKey)
        bridgeId = bridgeMgr.create(new BridgeConfig())
        bridgeConfig = bridgeMgr.get(bridgeId)
        assertEquals(2, bridgeConfig.tunnelKey)
        bridgeId = bridgeMgr.create(new BridgeConfig())
        bridgeConfig = bridgeMgr.get(bridgeId)
        assertEquals(3, bridgeConfig.tunnelKey)
    }
}
