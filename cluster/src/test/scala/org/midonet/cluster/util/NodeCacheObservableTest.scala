/*
 * Copyright 2015 Midokura SARL
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

import scala.concurrent.duration._

import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.retry.RetryOneTime
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, Suite}

import rx.observers.TestObserver

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class NodeCacheObservableTest extends Suite
                              with CuratorTestFramework
                              with Matchers {

    /** Create and delete a node, verify that the observable emits the right
      * contents */
    def testCreateDelete() {
        val path = makePath("1")

        val obs1 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        val obs2 = new TestObserver[ChildData] with AwaitableObserver[ChildData]

        val nco = NodeCacheObservable.create(curator, path)
        nco.subscribe(obs1)

        obs1.awaitOnNext(1, 1 second) shouldBe true
        obs1.getOnNextEvents should have size 1
        obs2.getOnNextEvents should have size 0

        nco.subscribe(obs2)
        obs2.awaitOnNext(1, 1 second) shouldBe true
        obs2.getOnNextEvents should have size 1

        obs1.getOnNextEvents.get(0).getData shouldBe "1".getBytes
        obs2.getOnNextEvents.get(0).getData shouldBe "1".getBytes

        nco.current.getData should be ("1".getBytes)

        curator.delete().forPath(path)

        obs1.awaitOnNext(2, 1 second) shouldBe true
        obs2.awaitOnNext(2, 1 second) shouldBe true

        obs1.getOnNextEvents.get(1) shouldBe null
        obs2.getOnNextEvents.get(1) shouldBe null

        makePath("1")

        obs1.awaitOnNext(3, 1 second) shouldBe true
        obs2.awaitOnNext(3, 1 second) shouldBe true

        obs1.getOnNextEvents.get(2).getData shouldBe "1".getBytes
        obs2.getOnNextEvents.get(2).getData shouldBe "1".getBytes
    }

    def testNonExistentPath(): Unit = {
        val nco = NodeCacheObservable.create(curator, "/nonExistent")
        val obs = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        nco.subscribe(obs)
        obs.awaitOnNext(1, 1 second) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe null
        obs.getOnCompletedEvents shouldBe empty
        obs.getOnErrorEvents shouldBe empty
    }

    def testClosedCacheNotifiesOnCompleted(): Unit = {
        val path = makePath("3")
        val nco = NodeCacheObservable.create(curator, path)

        val obs1 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        nco.subscribe(obs1)
        obs1.awaitOnNext(1, 1 second) shouldBe true

        nco.close() // This will complete the observable
        obs1.awaitCompletion(1 second)
        obs1.getOnNextEvents should have size 1
        obs1.getOnCompletedEvents should have size 1
        obs1.getOnErrorEvents shouldBe empty

        val obs2 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        nco.subscribe(obs2)
        obs2.awaitCompletion(1 second)
        obs2.getOnNextEvents shouldBe empty
        obs2.getOnCompletedEvents should have size 1
        obs2.getOnErrorEvents shouldBe empty
    }
}

@RunWith(classOf[JUnitRunner])
class NodeCacheObservableConnectionTest extends Suite
                                        with CuratorTestFramework
                                        with Matchers {
    override protected val retryPolicy = new RetryOneTime(500)

    override def cnxnTimeoutMs = 3000
    override def sessionTimeoutMs = 10000

    def testLostConnectionTriggersComplete(): Unit = {
        val path = makePath("1")
        val onc = NodeCacheObservable.create(curator, path)

        val obs1 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        onc.subscribe(obs1)
        obs1.awaitOnNext(1, 1 second) shouldBe true

        zk.stop() // This will complete the observable after the timeout.
        obs1.awaitCompletion(cnxnTimeoutMs * 2 millis)

        obs1.getOnCompletedEvents should have size 1
        obs1.getOnNextEvents should have size 1
        obs1.getOnErrorEvents shouldBe empty

        // Later observers will complete immediately, the observable is useless.
        val obs2 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        onc.subscribe(obs2)
        obs2.awaitCompletion(1 second)

        obs2.getOnCompletedEvents should have size 1
        obs2.getOnNextEvents shouldBe empty
        obs2.getOnErrorEvents shouldBe empty
    }
}