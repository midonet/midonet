/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import org.apache.curator.RetryPolicy
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.KeeperException
import org.junit.Before
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.models.Devices.Bridge
import org.midonet.cluster.models.Devices.Chain
import org.midonet.cluster.models.Devices.Port
import org.midonet.cluster.models.Devices.Router
import org.midonet.cluster.models.Devices.Rule


/**
 * Tests for bulk CRUD operations with ZOOM-based Storage Service.
 */
@RunWith(classOf[JUnitRunner])
class ZoomBulkCrudTest extends StorageBulkCrudTest
                       with ZoomStorageServiceTester
                       with BeforeAndAfter {
    val zkPort = 2181
    val zkConnectionString = "127.0.0.1:" + zkPort
    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    def zkRootDir = "/zoomtest"
    def deviceClasses =
        Array(classOf[Bridge], classOf[Chain], classOf[Port], classOf[Router],
              classOf[Rule])

    val bindings = Array(
            new ZoomBinding(
                    classOf[Bridge], "inbound_filter_id", DeleteAction.CLEAR,
                    classOf[Chain], "bridge_ids", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Bridge], "outbound_filter_id", DeleteAction.CLEAR,
                    classOf[Chain], "bridge_ids", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Router], "port_ids", DeleteAction.CASCADE,
                    classOf[Port], "router_id", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Bridge], "port_ids", DeleteAction.CASCADE,
                    classOf[Port], "bridge_id", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Port], "peer_uuid", DeleteAction.CLEAR,
                    classOf[Port], "peer_uuid", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Chain], "rule_ids", DeleteAction.CASCADE,
                    classOf[Rule], "chain_id", DeleteAction.CLEAR)
            )

    before {
        // Curator is started inside ZOOM, so it needs to be created per
        // ZOOM instance
        curator = CuratorFrameworkFactory.newClient(zkConnectionString,
                                                   retryPolicy)
        zoom = new ZookeeperObjectMapper(zkRootDir, curator)

        // Directories can be cleaned up only after Zoom has been created, and
        // whereby the CuratorFramework instance is started.
        try {
            cleanUpDirectories()
        } catch {
            case e0: Exception =>
                log.error("Failed to clean up directories {}", e0)
                fail()
        }

        registerClasses(deviceClasses, bindings)
    }

    after {
        log.info("Stopping the CuratorFramework.")
        curator.close()
    }
}
