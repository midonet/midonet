/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.util.concurrent.TimeUnit

import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.scalatest.{Suite, BeforeAndAfterAll, BeforeAndAfter}

import scala.collection.concurrent.TrieMap

/**
 * Provides boilerplate for:
 * 1. Starting up a Curator TestingServer.
 * 2. Connecting a Curator client to the TestingServer.
 * 3. Deleting data on the TestingServer before and after each test.
 *
 * Only one TestingServer is created for each test class.
 *
 * To add additional per-test setup/teardown behavior, override the setup()
 * and teardown() methods. There's no need to call super.setup() or
 * super.teardown().
 *
 * To add additional per-class setup/teardown behavior, override the
 * beforeAll() and afterAll() methods. For these, it is necessary to call the
 * super.beforeAll() and super.afterAll() methods. The inconsistency is due
 * to an inconsistency in the way scalatest's BeforeAndAfter and
 * BeforeAndAfterAll traits work.
 */
trait CuratorTestFramework extends BeforeAndAfter
                           with BeforeAndAfterAll { this: Suite =>
    import CuratorTestFramework.testServers

    protected val ZK_ROOT = "/test"
    protected val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    protected var zk: TestingServer = _
    protected var curator: CuratorFramework = _

    override protected def beforeAll(): Unit = {
        super.beforeAll()
        val ts = new TestingServer
        testServers(this.getClass) = ts
        ts.start()
    }

    protected def setup(): Unit = {}

    protected def teardown(): Unit = {}

    /**
     * Sets up a new CuratorFramework client against a in-memory test ZooKeeper
     * server. If one needs to test against a different ZooKeeper setup, he/she
     * should override this method.
     */
    protected def setUpCurator(): Unit = {
        zk = testServers(this.getClass)
        curator = CuratorFrameworkFactory.newClient(zk.getConnectString,
                                                    1000, 1000, retryPolicy)
    }

    before {
        setUpCurator()
        curator.start()
        if (!curator.blockUntilConnected(1000, TimeUnit.SECONDS))
           fail("Curator did not connect to the test ZK server")

        clearZookeeper()
        curator.create().forPath(ZK_ROOT)

        setup()
    }

    after {
        clearZookeeper()
        curator.close()
        teardown()
    }

    protected def clearZookeeper() =
        try curator.delete().deletingChildrenIfNeeded().forPath(ZK_ROOT) catch {
            case _: InterruptedException =>
                fail("Curator did not connect to the test ZK server")
            case _: Throwable => // OK, doesn't exist
        }

    override protected def afterAll(): Unit = {
        super.afterAll()
        testServers.remove(this.getClass).foreach(_.close())
    }
}

object CuratorTestFramework {
    protected val testServers =
        new TrieMap[Class[_ <: CuratorTestFramework], TestingServer]
}