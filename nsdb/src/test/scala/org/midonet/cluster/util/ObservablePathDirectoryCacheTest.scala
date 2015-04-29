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
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ObservablePathDirectoryCacheTest extends FeatureSpec
                                       with CuratorTestFramework
                                       with Matchers {

    val parentPath = ZK_ROOT + "/parent"

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

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
            opdc.subscribe(obs)
            obs.awaitOnNext(1, 1.second)

            obs.getOnNextEvents should contain only Set()
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Notification of existing children") {
            createParent()
            createChildren(0, 4)

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
            opdc.subscribe(obs)
            obs.awaitOnNext(1, 1.second)

            obs.getOnNextEvents should contain only Set("0", "1", "2", "3", "4")
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Notification of added children") {
            createParent()

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]
            opdc.subscribe(obs)

            obs.awaitOnNext(1, 1.second)
            createChild(0)
            obs.awaitOnNext(2, 1.second)
            createChild(1)
            obs.awaitOnNext(3, 1.second)
            createChild(2)
            obs.awaitOnNext(4, 1.second)

            obs.getOnNextEvents should contain inOrderOnly(
                Set(), Set("0"), Set("0", "1"), Set("0", "1", "2"))
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Notification of deleted children") {
            createParent()
            createChildren(0, 4)

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            opdc.subscribe(obs)
            obs.awaitOnNext(1, 1.second)
            deleteChild(0)
            obs.awaitOnNext(2, 1.second)
            deleteChild(1)
            obs.awaitOnNext(3, 1.second)

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

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            opdc.subscribe(obs)
            obs.awaitOnNext(1, 1.second)
            opdc.close()
            obs.awaitCompletion(1.second)

            obs.getOnNextEvents should contain only Set()
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                DirectoryCacheDisconnectedException]
            obs.getOnCompletedEvents shouldBe empty
        }
    }

    feature("Test observable errors") {
        scenario("Error on parent does not exist") {
            val path = ZK_ROOT + "/none"
            val opdc = ObservablePathDirectoryCache.create(curator, path)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            opdc.subscribe(obs)
            obs.awaitCompletion(1.second)

            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Error on parent deletion") {
            createParent()

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            opdc.subscribe(obs)
            obs.awaitOnNext(1, 1.second)

            deleteParent()
            obs.awaitCompletion(1.second)

            obs.getOnNextEvents should contain only Set()
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
        }

        scenario("Error on connect failure") {
            curator.close()

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            opdc.subscribe(obs)
            obs.awaitCompletion(1.second)

            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                DirectoryCacheDisconnectedException]
        }

        scenario("Error on lost connection") {
            createParent()

            val opdc = ObservablePathDirectoryCache.create(curator, parentPath)
            val obs = new TestObserver[Set[String]] with AwaitableObserver[Set[String]]

            opdc.subscribe(obs)
            obs.awaitOnNext(1, 1.second)
            zk.stop()
            obs.awaitCompletion(30.seconds)

            obs.getOnNextEvents should contain only Set()
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                DirectoryCacheDisconnectedException]
            curator.close()
        }
    }
}
