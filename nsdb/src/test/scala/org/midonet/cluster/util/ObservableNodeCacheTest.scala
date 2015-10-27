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
package org.midonet.cluster.util

import java.util.UUID
import java.util.concurrent.TimeUnit

import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.retry.RetryOneTime
import org.apache.zookeeper.KeeperException.NoNodeException
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.util.ObservableTestUtils.observer

@RunWith(classOf[JUnitRunner])
class ObservableNodeCacheTest extends Suite
                              with CuratorTestFramework
                              with Matchers {

    private val timeoutInSeconds = 5

    def testNoNodeAsEmpty(): Unit = {
        val path = s"/${UUID.randomUUID}"
        val sub = observer[ChildData](1, 0, 0)

        val onc = new ObservableNodeCache(curator, path, emitNoNodeAsEmpty = true)
        onc.connect()
        onc.observable.subscribe(sub)

        sub.n.await(timeoutInSeconds, TimeUnit.SECONDS)
        sub.getOnNextEvents should have size 1
        sub.getOnNextEvents.get(0) should be (onc.EMPTY_DATA)

        curator.create().inBackground().forPath(path, "a".getBytes)

        sub.reset(nNexts = 1, nErrors = 0, nCompletes = 0)
        sub.n.await(timeoutInSeconds, TimeUnit.SECONDS)
        sub.getOnNextEvents should have size 2
        sub.getOnNextEvents.get(1) should not be (onc.EMPTY_DATA)
        new String(sub.getOnNextEvents.get(1).getData) should be ("a")

        curator.delete().inBackground().forPath(path)

        sub.reset(nNexts = 1, nErrors = 0, nCompletes = 0)
        sub.n.await(timeoutInSeconds, TimeUnit.SECONDS)
        sub.getOnNextEvents should have size 3
        sub.getOnNextEvents.get(2) should be (onc.EMPTY_DATA)

        curator.create().inBackground().forPath(path, "b".getBytes)

        sub.reset(nNexts = 1, nErrors = 0, nCompletes = 0)
        sub.n.await(timeoutInSeconds, TimeUnit.SECONDS)
        sub.getOnNextEvents should have size 4
        sub.getOnNextEvents.get(3) should not be (onc.EMPTY_DATA)
        new String(sub.getOnNextEvents.get(3).getData) should be ("b")

        sub.getOnErrorEvents should have size 0
        sub.getOnCompletedEvents should have size 0
    }

    /** Create and delete a node, verify that the observable emits the right
      * contents */
    def testCreateDelete() {
        val path = makePath("1")

        val sub1 = observer[ChildData](1, 0, 1) // Will subscribe before connect
        val sub2 = observer[ChildData](1, 0, 1) // Will subscribe after connect

        val onc = new ObservableNodeCache(curator, path)
        onc.observable.subscribe(sub1)
        onc.connect()

        assert(sub1.n.await(timeoutInSeconds, TimeUnit.SECONDS))
        sub1.getOnNextEvents should have size 1
        sub2.getOnNextEvents should have size 0

        onc.observable.subscribe(sub2)
        assert(sub2.n.await(timeoutInSeconds, TimeUnit.SECONDS))

        "1".getBytes shouldBe sub1.getOnNextEvents.get(0).getData
        "1".getBytes shouldBe sub2.getOnNextEvents.get(0).getData

        onc.current.getData should be ("1".getBytes)

        curator delete() forPath path

        assert(sub1.c.await(timeoutInSeconds, TimeUnit.SECONDS))
        assert(sub2.c.await(timeoutInSeconds, TimeUnit.SECONDS))

        List(sub1, sub2) foreach { s =>
            s.getOnErrorEvents shouldBe empty
            s.getOnCompletedEvents should have size 1
            s.getOnNextEvents should have size 1
        }
    }

    def testNonExistentPathOnErrors(): Unit = {
        val onc = new ObservableNodeCache(curator, "/nonExistent")
        val s = observer[ChildData](0, 1, 0)
        onc.observable.subscribe(s)
        onc.connect()
        assertTrue(s.e.await(timeoutInSeconds, TimeUnit.SECONDS))
        s.getOnNextEvents shouldBe empty
        s.getOnCompletedEvents shouldBe empty
        s.getOnErrorEvents should have size 1
        assert(s.getOnErrorEvents.get(0).isInstanceOf[NoNodeException])
    }

    def testTwoConnectsAreOk(): Unit = {
        val path = makePath("3")
        val onc = new ObservableNodeCache(curator, path)
        val sub = observer[ChildData](1, 0, 0)
        onc.observable.subscribe(sub)
        onc.connect()
        onc.connect()
        assert(sub.n.await(timeoutInSeconds, TimeUnit.SECONDS))
        sub.getOnErrorEvents shouldBe empty
        sub.getOnCompletedEvents shouldBe empty
        sub.getOnNextEvents should have size 1

    }

    def testClosedCacheNotifiesOnError(): Unit = {
        val path = makePath("3")
        val onc = new ObservableNodeCache(curator, path)

        val ts = observer[ChildData](1, 1, 0)
        onc.observable.subscribe(ts)
        onc.connect()  // This will send us the element
        assertTrue(ts.n.await(timeoutInSeconds, TimeUnit.SECONDS))

        onc.close()    // This will send us the error
        assertTrue(ts.e.await(timeoutInSeconds, TimeUnit.SECONDS))

        ts.getOnCompletedEvents shouldBe empty
        ts.getOnNextEvents should have size 1
        ts.getOnErrorEvents should have size 1
        assert(ts.getOnErrorEvents.get(0).isInstanceOf[NodeCacheDisconnected])

        val ts2 = observer[ChildData](0, 1, 0)
        onc.observable.subscribe(ts2)
        ts2.e.await(timeoutInSeconds, TimeUnit.SECONDS)
        ts2.getOnNextEvents shouldBe empty
        ts2.getOnCompletedEvents shouldBe empty
        ts2.getOnErrorEvents should have size 1
        assert(ts2.getOnErrorEvents.get(0).isInstanceOf[NodeCacheDisconnected])

    }
}

/** Tests related to connection failures handling that tweak session and cnxn
  * timeouts. */
@RunWith(classOf[JUnitRunner])
class ObservableNodeCacheConnectionTest extends Suite
                                        with CuratorTestFramework
                                        with Matchers {

    override protected val retryPolicy = new RetryOneTime(500)

    private val timeoutInSeconds = 5

    override def cnxnTimeoutMs = 3000
    override def sessionTimeoutMs = 10000

    def testLostConnectionTriggersError(): Unit = {
        val path = makePath("1")
        val onc = new ObservableNodeCache(curator, path)

        val ts = observer[ChildData](1, 1, 0)
        onc.observable.subscribe(ts)
        onc.connect()  // This will send us the element
        assertTrue(ts.n.await(timeoutInSeconds, TimeUnit.SECONDS))

        zk.stop()      // This will send us the error after the cnxn times out
        assertTrue(ts.e.await(cnxnTimeoutMs * 2, TimeUnit.MILLISECONDS))

        ts.getOnCompletedEvents shouldBe empty
        ts.getOnNextEvents should have size 1
        ts.getOnErrorEvents should have size 1
        assert(ts.getOnErrorEvents.get(0).isInstanceOf[NodeCacheDisconnected])

        // Later observers will also get the same error, the Observable is now
        // useless
        val ts2 = observer[ChildData](0, 1, 0)
        onc.observable.subscribe(ts2)
        ts2.e.await(timeoutInSeconds, TimeUnit.SECONDS)
        ts2.getOnNextEvents shouldBe empty
        ts2.getOnCompletedEvents shouldBe empty
        ts2.getOnErrorEvents should have size 1
        assert(ts2.getOnErrorEvents.get(0).isInstanceOf[NodeCacheDisconnected])

    }
}

