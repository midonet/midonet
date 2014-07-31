/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.retry.ExponentialBackoffRetry

import rx.observers.TestObserver
import org.apache.curator.test.TestingServer

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.junit.{AfterClass, BeforeClass}

@RunWith(classOf[JUnitRunner])
class ObservableNodeCacheTest extends Suite
                              with Matchers
                              with BeforeAndAfter
                              with BeforeAndAfterAll {

    val ROOT = "/test"

    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    var curator: CuratorFramework = null
    var zk: TestingServer = _

    override def beforeAll () {
        zk = new TestingServer()
        zk.start()
    }

    override def afterAll() { zk.close() }


    before {
        curator = CuratorFrameworkFactory.newClient(zk.getConnectString,
                                                    retryPolicy)
        try {
            curator.delete().deletingChildrenIfNeeded().forPath(ROOT)
        } catch {
            case _: Throwable => // OK, doesn't exist
        }
        curator.start()
    }

    after {
        try {
            curator.delete().deletingChildrenIfNeeded().forPath(ROOT)
            curator.close()
        } catch {
            case _: Throwable =>  // ok, probably
        }
    }

    /** Create and delete a node, verify that the observable emits the right
      * contents */
    def testCreateDelete() {
        curator.create().forPath(ROOT, "1".getBytes)
        Thread.sleep(500)

        val onc = new ObservableNodeCache(curator)
        onc connect ROOT
        val cd = new TestObserver[ChildData]()
        onc.observable().subscribe(cd)
        Thread sleep 500

        cd.getOnNextEvents should have size 1
        cd.getOnNextEvents.get(0).getData should have size 1
        onc.current.getData should be ("1".getBytes)

        curator delete() forPath ROOT
        Thread sleep 500

        cd.getOnErrorEvents should be (empty)
        cd.getOnCompletedEvents should have size 1
    }
}
