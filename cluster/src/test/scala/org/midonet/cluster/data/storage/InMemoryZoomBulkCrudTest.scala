/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Tests for bulk CRUD operations with ZOOM-based Storage Service using
 * in-memory ZooKeeper.
 */
@RunWith(classOf[JUnitRunner])
class InMemoryZoomBulkCrudTest extends ZoomBulkCrudTest
                               with BeforeAndAfterAll {
    var testingServer: TestingServer = null

    override def beforeAll() {
        log.info("Starting an in-memory test ZK server.")
        testingServer = new TestingServer(zkPort)
    }

    override def afterAll() {
        super.afterAll()
        log.info("Shutting down the test ZK server.")
        testingServer.close()
    }
}
