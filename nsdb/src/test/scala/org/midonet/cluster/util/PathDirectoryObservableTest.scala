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

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry

import org.apache.curator.retry.RetryOneTime
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class PathDirectoryObservableTest extends FlatSpec
                                  with CuratorTestFramework
                                  with Matchers {

    private val parentPath = zkRoot + "/parent"
    private val timeout = 5 seconds
    private val metrics = new StorageMetrics(zoom = null, new MetricRegistry)

    def createParent(): String = {
        curator.create.forPath(parentPath)
    }

    def deleteParent(): Unit = {
        curator.delete().forPath(parentPath)
    }

    def createChild(index: Int): String = {
        val path = parentPath + "/" + index
        curator.create().forPath(path)
        path
    }

    def createChildren(start: Int, end: Int): Set[String] = {
        var paths = Set.empty[String]
        for (index <- start to end) {
            paths += createChild(index)
        }
        paths
    }

    def deleteChild(index: Int): Unit = {
        curator.delete().forPath(parentPath + "/" + index)
    }

    "Directory observable" should "notify empty children set" in {
        createParent()

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout) shouldBe true

        obs.getOnNextEvents should contain only Set()
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
    }

    "Directory observable" should "notify existing children" in {
        createParent()
        createChildren(0, 4)

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout) shouldBe true

        obs.getOnNextEvents should contain only Set("0", "1", "2", "3", "4")
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
    }

    "Directory observable" should "notify added children" in {
        createParent()

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
        observable.subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        createChild(0)
        obs.awaitOnNext(2, timeout) shouldBe true
        createChild(1)
        obs.awaitOnNext(3, timeout) shouldBe true
        createChild(2)
        obs.awaitOnNext(4, timeout) shouldBe true

        obs.getOnNextEvents should contain inOrderOnly(
            Set(), Set("0"), Set("0", "1"), Set("0", "1", "2"))
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
    }

    "Directory observable" should "notify deleted children" in {
        createParent()
        createChildren(0, 4)

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout) shouldBe true
        deleteChild(0)
        obs.awaitOnNext(2, timeout) shouldBe true
        deleteChild(1)
        obs.awaitOnNext(3, timeout) shouldBe true

        obs.getOnNextEvents should contain inOrderOnly(
            Set("0", "1", "2", "3", "4"), Set("1", "2", "3", "4"),
            Set("2", "3", "4"))
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
    }

    "Directory observable" should "emit error on cache close" in {
        createParent()

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout) shouldBe true
        observable.close()
        obs.awaitCompletion(timeout)

        obs.getOnNextEvents should contain only Set()
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            DirectoryObservableClosedException]
        obs.getOnCompletedEvents shouldBe empty
    }

    "Directory observable" should "call onClose handler" in {
        createParent()

        val closed = new AtomicBoolean()
        val observable = PathDirectoryObservable.create(
            curator, parentPath, metrics, completeOnDelete = true, {
                closed set true
            })
        val obs1 = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
        val obs2 = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        val sub1 = observable.subscribe(obs1)
        val sub2 = observable.subscribe(obs2)

        obs1.awaitOnNext(1, timeout) shouldBe true
        obs2.awaitOnNext(1, timeout) shouldBe true

        sub1.unsubscribe()
        closed.get shouldBe false

        sub2.unsubscribe()
        closed.get shouldBe true
    }

    "Complete on delete directory observable" should
    "emit error if the parent does not exist" in {
        val path = zkRoot + "/none"
        val observable = PathDirectoryObservable.create(curator, path, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitCompletion(timeout)

        obs.getOnNextEvents shouldBe empty
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            ParentDeletedException]
        obs.getOnCompletedEvents shouldBe empty
    }

    "Complete on delete directory observable" should
    "emit error on parent deletion" in {
        createParent()

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout) shouldBe true

        deleteParent()
        obs.awaitCompletion(timeout)

        obs.getOnNextEvents should contain only Set()
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            ParentDeletedException]
    }

    "Continue on delete directory observable" should
    "monitor parent if the parent does not exist" in {
        val observable = PathDirectoryObservable.create(curator, parentPath,
                                                        metrics,
                                                        completeOnDelete = false)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout)

        obs.getOnNextEvents should contain only Set()
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty

        createParent()
        obs.awaitOnNext(2, timeout)
        createChild(0)
        obs.awaitOnNext(3, timeout)
        createChild(1)
        obs.awaitOnNext(4, timeout)

        obs.getOnNextEvents should contain theSameElementsInOrderAs List(
            Set(), Set(), Set("0"), Set("0", "1"))
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
    }

    "Continue on delete directory observable" should
    "monitor parent after the parent is deleted" in {
        createParent()
        createChild(0)

        val observable = PathDirectoryObservable.create(curator, parentPath,
                                                        metrics,
                                                        completeOnDelete = false)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout)

        obs.getOnNextEvents should contain only Set("0")
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty

        deleteChild(0)
        obs.awaitOnNext(2, timeout)
        deleteParent()
        obs.awaitOnNext(3, timeout)

        obs.getOnNextEvents should contain theSameElementsInOrderAs List(
            Set("0"), Set(), Set())
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty

        createParent()
        obs.awaitOnNext(4, timeout)

        obs.getOnNextEvents should contain theSameElementsInOrderAs List(
            Set("0"), Set(), Set(), Set())
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty

        createChild(1)
        obs.awaitOnNext(5, timeout)

        obs.getOnNextEvents should contain theSameElementsInOrderAs List(
            Set("0"), Set(), Set(), Set(), Set("1"))
        obs.getOnErrorEvents shouldBe empty
        obs.getOnCompletedEvents shouldBe empty
    }

    "Directory observable" should "emit error on connect failure" in {
        curator.close()

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitCompletion(timeout)

        obs.getOnNextEvents shouldBe empty
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            DirectoryObservableDisconnectedException]
    }

    "Directory observable" should "emit error on lost connection" in {
        createParent()

        val observable = PathDirectoryObservable.create(curator, parentPath, metrics)
        val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

        observable.subscribe(obs)
        obs.awaitOnNext(1, timeout) shouldBe true
        zk.stop()
        obs.awaitCompletion(30 seconds)

        obs.getOnNextEvents should contain only Set()
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            DirectoryObservableDisconnectedException]
        curator.close()
    }
}

/** Tests related to connection failures handling that tweak session and
  * connection timeouts. */
@RunWith(classOf[JUnitRunner])
class PathDirectoryObservableConnectionTest extends FlatSpec
                                            with CuratorTestFramework
                                            with Matchers {

    override protected val retryPolicy = new RetryOneTime(500)

    override def cnxnTimeoutMs = 3000
    override def sessionTimeoutMs = 10000
    private val timeout = 1 second
    private val metrics = new StorageMetrics(zoom = null, new MetricRegistry)

    "Directory observable" should "emit error on losing connection" in {
        val path = makePath("1")
        val observable = PathDirectoryObservable.create(curator, path, metrics)

        val obs1 = new TestObserver[Set[String]]
                       with AwaitableObserver[Set[String]]
        observable.subscribe(obs1)
        obs1.awaitOnNext(1, timeout)

        zk.stop()      // This will send us the error after the cnxn times out
        obs1.awaitCompletion(cnxnTimeoutMs * 2 millis)

        obs1.getOnCompletedEvents shouldBe empty
        obs1.getOnNextEvents should have size 1
        obs1.getOnErrorEvents should have size 1
        obs1.getOnErrorEvents.get(0).getClass shouldBe classOf[
            DirectoryObservableDisconnectedException]

        // Later observers will also get a cache closed error, the Observable is
        // now useless
        val obs2 = new TestObserver[Set[String]]
                       with AwaitableObserver[Set[String]]
        observable.subscribe(obs2)
        obs2.awaitCompletion(timeout)

        obs2.getOnNextEvents shouldBe empty
        obs2.getOnCompletedEvents shouldBe empty
        obs2.getOnErrorEvents should have size 1
        obs2.getOnErrorEvents.get(0).getClass shouldBe classOf[
            DirectoryObservableClosedException]
    }
}