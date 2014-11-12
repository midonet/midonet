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

import java.util.concurrent.CountDownLatch

import org.apache.curator.framework.recipes.cache.ChildData
import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import rx.Subscriber
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.NotFoundException

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

        val onc = new ObservableNodeCache(curator, path)
        // Will subscribe before connect
        val sub1 = new TestObserver[ChildData]()
        // Will subscribe after connect
        val sub2 = new TestObserver[ChildData]()

        onc.observable.subscribe(sub1)

        Thread sleep 500

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

    @Test(timeout = 500)
    def testNonExistent(): Unit = {
        val c = new CountDownLatch(1)
        val onc = new ObservableNodeCache(curator, "/nonExistent")
        onc.observable.subscribe(new Subscriber[ChildData]() {
            override def onCompleted(): Unit = fail("unexpected onComplete")
            override def onError(e: Throwable): Unit = {
                c.countDown()
                e match {
                    case _: NotFoundException =>
                    case _ => fail("Unexpected error " + e)
                }
            }
            override def onNext(t: ChildData): Unit = fail("unexpected onNext")
        })
        c.await()
    }
}
