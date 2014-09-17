/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.util.CuratorTestFramework

/**
 * Tests for bulk CRUD operations with ZOOM-based Storage Service using
 * in-memory ZooKeeper.
 */
@RunWith(classOf[JUnitRunner])
class InMemoryZoomBulkCrudTest extends StorageBulkCrudTest
                               with ZoomStorageServiceTester
                               with CuratorTestFramework {
}
