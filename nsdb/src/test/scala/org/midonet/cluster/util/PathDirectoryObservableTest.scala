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

import scala.concurrent.duration._

import org.apache.curator.retry.RetryOneTime
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class PathDirectoryObservableTest extends FeatureSpec
                                  with CuratorTestFramework
                                  with Matchers {

    val parentPath = ZK_ROOT + "/parent"
    val timeout = 5 seconds

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

    feature("Test children notification") {

        scenario("Notification of empty children set") {
            createParent()

            val observable = PathDirectoryObservable.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
            observable.subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            obs.getOnNextEvents should contain only Set()
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Notification of existing children") {
            createParent()
            createChildren(0, 4)

            val observable = PathDirectoryObservable.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
            observable.subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            obs.getOnNextEvents should contain only Set("0", "1", "2", "3", "4")
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Notification of added children") {
            createParent()

            val observable = PathDirectoryObservable.create(curator, parentPath)
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

        scenario("Notification of deleted children") {
            createParent()
            createChildren(0, 4)

            val observable = PathDirectoryObservable.create(curator, parentPath)
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
    }

    feature("Test cache close") {
        scenario("Error on cache close") {
            createParent()

            val observable = PathDirectoryObservable.create(curator, parentPath)
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
    }

    feature("Test observable errors") {
        scenario("Error on parent does not exist") {
            val path = ZK_ROOT + "/none"
            val observable = PathDirectoryObservable.create(curator, path)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            observable.subscribe(obs)
            obs.awaitCompletion(timeout)

            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Error on parent deletion") {
            createParent()

            val observable = PathDirectoryObservable.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            observable.subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            deleteParent()
            obs.awaitCompletion(timeout)

            obs.getOnNextEvents should contain only Set()
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
        }

        scenario("Error on connect failure") {
            curator.close()

            val observable = PathDirectoryObservable.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            observable.subscribe(obs)
            obs.awaitCompletion(timeout)

            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                DirectoryObservableDisconnectedException]
        }

        scenario("Error on lost connection") {
            createParent()

            val observable = PathDirectoryObservable.create(curator, parentPath)
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

    "Directory observable" should "emit error on losing connection" in {
        val path = makePath("1")
        val observable = PathDirectoryObservable.create(curator, path)

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