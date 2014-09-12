/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import org.apache.curator.framework.recipes.cache.ChildData

import rx.observers.TestObserver

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ObservableNodeCacheTest extends Suite
                              with CuratorTestFramework
                              with Matchers {
    /** Create and delete a node, verify that the observable emits the right
      * contents */
    def testCreateDelete() {
        val path = ZK_ROOT + "/1"
        curator.create().forPath(path, "1".getBytes)
        Thread.sleep(500)

        val onc = new ObservableNodeCache(curator)
        onc connect path
        val cd = new TestObserver[ChildData]()
        onc.observable.subscribe(cd)
        Thread sleep 500

        cd.getOnNextEvents should have size 1
        cd.getOnNextEvents.get(0).getData should have size 1
        onc.current.getData should be ("1".getBytes)

        curator delete() forPath path
        Thread sleep 500

        cd.getOnErrorEvents should be (empty)
        cd.getOnCompletedEvents should have size 1
    }
}
