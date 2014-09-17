/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import org.apache.curator.framework.CuratorFrameworkFactory
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
 * Tests for bulk CRUD operations with ZOOM-based Storage Service.
 */
@RunWith(classOf[JUnitRunner])
class ZoomBulkCrudTest extends StorageBulkCrudTest
                       with ZoomStorageServiceTester {
    protected val ZK_PORT = 2181
    protected val ZK_CONNECT_STRING = "127.0.0.1:" + ZK_PORT

    /**
     * Sets up a new CuratorFramework client against a local ZooKeeper setup.
     */
    override protected def setUpCurator(): Unit = {
        curator = CuratorFrameworkFactory.newClient(ZK_CONNECT_STRING,
                                                    retryPolicy)
    }
}
