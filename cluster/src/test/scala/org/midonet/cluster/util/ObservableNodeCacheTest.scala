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

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.apache.curator.framework.recipes.cache.ChildData
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import rx.Subscriber
import rx.observers.{TestSubscriber, TestObserver}

@RunWith(classOf[JUnitRunner])
class ObservableNodeCacheTest extends Suite
                              with CuratorTestFramework
                              with Matchers {

    private def makePath(s: String): String = {
        val path = ZK_ROOT + "/" + s
        curator.create().forPath(path, s.getBytes)
        curator.getData.forPath(path)
        path
    }

    /** Create and delete a node, verify that the observable emits the right
      * contents */
    def testCreateDelete() {
        val path = makePath("1")

        val onc = new ObservableNodeCache(curator, path)

        // Will subscribe before connect
        val sub1 = new TestObserver[ChildData]()
        // Will subscribe after connect
        val sub2 = new TestObserver[ChildData]()

        onc.observable.subscribe(sub1)
        onc.connect()

        Thread.sleep(500)

        sub1.getOnNextEvents should have size 1
        sub2.getOnNextEvents should have size 0

        onc.observable.subscribe(sub2)

        Thread sleep 500

        sub1.getOnNextEvents should have size 1
        sub2.getOnNextEvents should have size 1

        sub1.getOnNextEvents.get(0).getData should have size 1
        sub2.getOnNextEvents.get(0).getData should have size 1
        onc.current.getData should be ("1".getBytes)

        curator delete() forPath path
        Thread sleep 500

        List(sub1, sub2) foreach { s =>
            s.getOnErrorEvents should be (empty)
            s.getOnCompletedEvents should have size 1
        }
    }

    def testNonExistentPathOnErrors(): Unit = {
        val c = new CountDownLatch(1)
        val onc = new ObservableNodeCache(curator, "/nonExistent")
        val s = new TestObserver[ChildData]() {
            override def onError(e: Throwable): Unit = {
                super.onError(e)
                c.countDown()
                e match {
                    case _: NodeCacheOrphaned => // ok
                    case _ => fail("Unexpected error " + e)
                }
            }
        }
        onc.observable.subscribe(s)
        onc.connect()
        assertTrue(c.await(500, TimeUnit.MILLISECONDS))
        s.getOnNextEvents shouldBe empty
        s.getOnCompletedEvents shouldBe empty
    }

    def testTwoConnectsAreOk(): Unit = {
        val n = new CountDownLatch(1)
        val path = makePath("3")
        val onc = new ObservableNodeCache(curator, path)
        val sub = new TestObserver[ChildData]() {
            override def onNext(t: ChildData): Unit = {
                super.onNext(t)
                n.countDown()
            }
        }
        onc.observable.subscribe(sub)
        onc.connect()
        onc.connect()
        assertTrue(n.await(500, TimeUnit.MILLISECONDS))
        sub.getOnErrorEvents shouldBe empty
        sub.getOnCompletedEvents shouldBe empty

    }

    def testClosedCacheNotifiesOnError(): Unit = {
        val n = new CountDownLatch(1)
        val e = new CountDownLatch(1)
        val s = new TestObserver[ChildData]() {
            override def onError(t: Throwable): Unit = {
                super.onError(t)
                e.countDown()
            }
            override def onNext(t: ChildData): Unit = {
                super.onNext(t)
                n.countDown()
            }
        }
        val path = makePath("3")
        val onc = new ObservableNodeCache(curator, path)
        onc.observable.subscribe(s)

        onc.connect()
        assertTrue(n.await(500, TimeUnit.MILLISECONDS))
        onc.close()
        assertTrue(e.await(500, TimeUnit.MILLISECONDS))

        s.getOnCompletedEvents shouldBe empty
        s.getOnNextEvents should have size 1
        s.getOnErrorEvents should have size 1
    }
}
