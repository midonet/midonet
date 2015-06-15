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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.util.reactivex.TestAwaitableObserver

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
            val obs = new TestAwaitableObserver[Set[String]]
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
            val obs = new TestAwaitableObserver[Set[String]]
            observable.subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            obs.getOnNextEvents should contain only Set("0", "1", "2", "3", "4")
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Notification of added children") {
            createParent()

            val observable = PathDirectoryObservable.create(curator, parentPath)
            val obs = new TestAwaitableObserver[Set[String]]
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
            val obs = new TestAwaitableObserver[Set[String]]

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
            val obs = new TestAwaitableObserver[Set[String]]

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
            val obs = new TestAwaitableObserver[Set[String]]

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
            val obs = new TestAwaitableObserver[Set[String]]

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
            val obs = new TestAwaitableObserver[Set[String]]

            observable.subscribe(obs)
            obs.awaitCompletion(timeout)

            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                DirectoryObservableDisconnectedException]
        }

        scenario("Error on lost connection") {
            createParent()

            val observable = PathDirectoryObservable.create(curator, parentPath)
            val obs = new TestAwaitableObserver[Set[String]]

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
