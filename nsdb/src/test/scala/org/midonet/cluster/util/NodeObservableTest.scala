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
import org.apache.zookeeper.KeeperException.NoNodeException
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class NodeObservableTest extends FlatSpec with CuratorTestFramework
                         with Matchers {

    "Node observable" should "emit notifications in create / delete" in {
        val path = makePath("1")

        val obs1 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        val obs2 = new TestObserver[ChildData] with AwaitableObserver[ChildData]

        val observable = NodeObservable.create(curator, path)

        observable.subscribe(obs1)
        obs1.awaitOnNext(1, 1 second) shouldBe true

        observable.subscribe(obs2)
        obs2.awaitOnNext(1, 1 second) shouldBe true

        obs1.getOnNextEvents.get(0).getData shouldBe "1".getBytes
        obs2.getOnNextEvents.get(0).getData shouldBe "1".getBytes

        observable.current.getData should be ("1".getBytes)

        curator delete() forPath path

        obs1.awaitCompletion(1 second)
        obs2.awaitCompletion(1 second)

        List(obs1, obs2) foreach { obs =>
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents should have size 1
            obs.getOnNextEvents should have size 1
        }
    }

    "Node observable" should "emit error if node does not exist" in {
        val observable = NodeObservable.create(curator, "/nonExistent")
        val obs = new TestObserver[ChildData] with AwaitableObserver[ChildData]

        observable.subscribe(obs)
        obs.awaitCompletion(1 second)

        obs.getOnNextEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
        obs.getOnErrorEvents should have size 1
        obs.getOnErrorEvents.get(0).isInstanceOf[NoNodeException] shouldBe true
    }

    "Node observable" should "emit error on close" in {
        val path = makePath("3")
        val observable = NodeObservable.create(curator, path)

        val obs1 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        observable.subscribe(obs1)
        obs1.awaitOnNext(1, 1 second) shouldBe true

        observable.close()    // This will send us the error
        obs1.awaitCompletion(1 second)

        obs1.getOnCompletedEvents shouldBe empty
        obs1.getOnNextEvents should have size 1
        obs1.getOnErrorEvents should have size 1
        obs1.getOnErrorEvents.get(0).isInstanceOf[NodeObservableClosedException] shouldBe true

        val obs2 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        observable.subscribe(obs2)
        obs2.awaitCompletion(1 second)

        obs2.getOnNextEvents shouldBe empty
        obs2.getOnCompletedEvents shouldBe empty
        obs2.getOnErrorEvents should have size 1
        obs2.getOnErrorEvents.get(0).isInstanceOf[NodeObservableClosedException] shouldBe true
    }
}

/** Tests related to connection failures handling that tweak session and cnxn
  * timeouts. */
@RunWith(classOf[JUnitRunner])
class NodeObservableConnectionTest extends FlatSpec with CuratorTestFramework
                                   with Matchers {

    override protected val retryPolicy = new RetryOneTime(500)

    override def cnxnTimeoutMs = 1000
    override def sessionTimeoutMs = 3000

    "Node observable" should "emit error on losing connection" in {
        val path = makePath("1")
        val observable = NodeObservable.create(curator, path)

        val obs1 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        observable.subscribe(obs1)
        obs1.awaitOnNext(1, 1 second)

        zk.stop()      // This will send us the error after the cnxn times out
        obs1.awaitCompletion(cnxnTimeoutMs * 2 millis)

        obs1.getOnCompletedEvents shouldBe empty
        obs1.getOnNextEvents should have size 1
        obs1.getOnErrorEvents should have size 1
        obs1.getOnErrorEvents.get(0).isInstanceOf[NodeObservableDisconnectedException] shouldBe true

        // Later observers will also get a cache closed error, the Observable is
        // now useless
        val obs2 = new TestObserver[ChildData] with AwaitableObserver[ChildData]
        observable.subscribe(obs2)
        obs2.awaitCompletion(1 second)

        obs2.getOnNextEvents shouldBe empty
        obs2.getOnCompletedEvents shouldBe empty
        obs2.getOnErrorEvents should have size 1
        obs2.getOnErrorEvents.get(0).isInstanceOf[NodeObservableClosedException] shouldBe true
    }
}
