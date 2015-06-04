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

package org.midonet.cluster.data.storage

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.zookeeper.KeeperException.Code
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.{GivenWhenThen, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.StorageTestClasses.State
import org.midonet.cluster.data.storage.StorageWithState.NoOwnerId
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.data.storage.WritePolicy.{Multiple, SingleFirstWriteWins, SingleLastWriteWins}
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectStateTest extends FeatureSpec with CuratorTestFramework
                               with Matchers with GivenWhenThen
                               with Eventually {

    private var storage: ZookeeperObjectMapper = _
    private final val timeout = 1 second

    protected override def setup(): Unit = {
        storage = new ZookeeperObjectMapper(ZK_ROOT, curator)
        initAndBuildStorage(storage)
    }

    private def initAndBuildStorage(storage: ZookeeperObjectMapper): Unit = {
        storage.registerClass(classOf[State])
        storage.registerKey(classOf[State], "first", SingleFirstWriteWins)
        storage.registerKey(classOf[State], "last", SingleLastWriteWins)
        storage.registerKey(classOf[State], "multi", Multiple)

        storage.build()
    }

    feature("Test state paths") {
        scenario("Root state path") {
            Given("The root state path")
            val path = storage.getStateClassPath(classOf[State])
            Then("The path should exist")
            curator.checkExists().forPath(path) should not be null
        }

        scenario("Object paths are created with object") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("The paths for the object state and each key")
            val objPath = storage.getStateObjectPath(classOf[State], obj.id)
            val key1Path = storage.getKeyPath(classOf[State], obj.id, "first")
            val key2Path = storage.getKeyPath(classOf[State], obj.id, "last")
            val key3Path = storage.getKeyPath(classOf[State], obj.id, "multi")

            Then("The path for the object should exist")
            curator.checkExists().forPath(objPath) should not be null
            And("The path for the single valued keys should not exist")
            curator.checkExists().forPath(key1Path) shouldBe null
            curator.checkExists().forPath(key2Path) shouldBe null
            And("The path for the multi valued keys should exist")
            curator.checkExists().forPath(key3Path) should not be null
        }

        scenario("Object paths are deleted with object") {
            Given("An object")
            val obj = new State
            When("The object is created in storage")
            storage.create(obj)
            And("The object is deleted from storage")
            storage.delete(classOf[State], obj.id)

            Then("Eventually the state path should be deleted")
            eventually {
                val objPath = storage.getStateObjectPath(classOf[State], obj.id)
                curator.checkExists().forPath(objPath) shouldBe null
            }
        }
    }

    feature("Test single value with first write wins") {
        scenario("Add for non-existing key") {
            Given("Any object identifier and non-existing key")
            val id = UUID.randomUUID()
            Then("Adding a value the method should throw an exception")
            intercept[IllegalArgumentException] {
                storage.addValue(classOf[State], id, "absent", "nonce")
            }
        }

        scenario("Get for non-existing key") {
            Given("Any object identifier and non-existing key")
            val id = UUID.randomUUID()
            Then("Reading the key the method should throw an exception")
            intercept[IllegalArgumentException] {
                storage.getKey(classOf[State], id, "absent")
            }
        }

        scenario("Remove for non-existing key") {
            Given("Any object identifier and non-existing key")
            val id = UUID.randomUUID()
            Then("Removing a value the method should throw an exception")
            intercept[IllegalArgumentException] {
                storage.removeValue(classOf[State], id, "absent", null)
            }
        }

        scenario("Add for non-existing object") {
            Given("A non-existing object identifier")
            val id = UUID.randomUUID
            Then("Adding a value, the future should fail")
            val e = intercept[StateException] {
                storage.addValue(classOf[State], id, "first", "nonce")
                       .await(timeout)
            }

            e.clazz shouldBe classOf[State].getSimpleName
            e.id shouldBe id.toString
            e.key shouldBe "first"
            e.value shouldBe "nonce"
            e.result shouldBe Code.NONODE.intValue()
        }

        scenario("Get for non-existing object") {
            Given("A non-existing object identifier")
            val id = UUID.randomUUID()
            Then("Reading the key, the future should fail")
            storage.getKey(classOf[State], id, "first")
                   .await(timeout) shouldBe SingleValueKey("first", None,
                                                           NoOwnerId)
        }

        scenario("Remove for non-existing object") {
            Given("A non-existing object identifier")
            val id = UUID.randomUUID()
            Then("Removing a value, the future should fail")
            storage.removeValue(classOf[State], id, "first", null)
                   .await(timeout) shouldBe StateResult(NoOwnerId)
        }

        scenario("Add, get, remove value for object") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            Then("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "first", "1")
                   .await(timeout) shouldBe StateResult(ownerId)
            And("Reading the key should return the added value")
            storage.getKey(classOf[State], obj.id, "first")
                   .await(timeout) shouldBe SingleValueKey("first", Some("1"),
                                                           ownerId)
            And("Removing the value should return the current session")
            storage.removeValue(classOf[State], obj.id, "first", null)
                   .await(timeout) shouldBe StateResult(ownerId)
            And("Reading the key again should return no value")
            storage.getKey(classOf[State], obj.id, "first")
                   .await(timeout) shouldBe SingleValueKey("first", None,
                                                           NoOwnerId)
        }

        scenario("Add another value for the same client") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            Then("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "first", "1")
                   .await(timeout) shouldBe StateResult(ownerId)
            And("Adding a second value should return the current session")
            storage.addValue(classOf[State], obj.id, "first", "2")
                   .await(timeout) shouldBe StateResult(ownerId)
            And("Reading the key should return the second value")
            storage.getKey(classOf[State], obj.id, "first")
                   .await(timeout) shouldBe SingleValueKey("first", Some("2"),
                                                           ownerId)
        }

        scenario("Add another value for a different client") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            And("A second storage client")
            val curator2 = CuratorFrameworkFactory.newClient(zk.getConnectString,
                                                             sessionTimeoutMs,
                                                             cnxnTimeoutMs,
                                                             retryPolicy)
            curator2.start()
            if (!curator2.blockUntilConnected(1000, TimeUnit.SECONDS))
                fail("Curator did not connect to the test ZK server")
            val storage2 = new ZookeeperObjectMapper(ZK_ROOT, curator2)
            initAndBuildStorage(storage2)

            Then("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "first", "1")
                   .await(timeout) shouldBe StateResult(ownerId)
            And("Adding a value from the second client should fail")
            val e = intercept[StateOwnershipException] {
                storage2.addValue(classOf[State], obj.id, "first", "2")
                        .await(timeout) shouldBe StateResult(ownerId)
            }

            e.clazz shouldBe classOf[State].getSimpleName
            e.id shouldBe obj.id.toString
            e.key shouldBe "first"
            e.owner shouldBe ownerId

            curator2.close()
        }

        scenario("Remove another value for a different client") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            And("A second storage client")
            val curator2 = CuratorFrameworkFactory.newClient(zk.getConnectString,
                                                             sessionTimeoutMs,
                                                             cnxnTimeoutMs,
                                                             retryPolicy)
            curator2.start()
            if (!curator2.blockUntilConnected(1000, TimeUnit.SECONDS))
                fail("Curator did not connect to the test ZK server")
            val storage2 = new ZookeeperObjectMapper(ZK_ROOT, curator2)
            initAndBuildStorage(storage2)

            Then("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "first", "1")
                   .await(timeout) shouldBe StateResult(ownerId)
            And("Removing the value from the second client should fail")
            val e = intercept[StateOwnershipException] {
                storage2.removeValue(classOf[State], obj.id, "first", null)
                        .await(timeout) shouldBe StateResult(ownerId)
            }

            e.clazz shouldBe classOf[State].getSimpleName
            e.id shouldBe obj.id.toString
            e.key shouldBe "first"
            e.owner shouldBe ownerId

            curator2.close()
        }
    }

}
