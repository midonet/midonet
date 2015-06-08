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

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.StorageTestClasses.State
import org.midonet.cluster.data.storage.StateStorage.NoOwnerId
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.data.storage.KeyType.{Multiple, SingleFirstWriteWins, SingleLastWriteWins}
import org.midonet.util.reactivex._
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectStateTest extends FeatureSpec with CuratorTestFramework
                               with Matchers with GivenWhenThen
                               with Eventually {

    private var storage: ZookeeperObjectMapper = _
    private final val timeout = 5 seconds

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

    feature("Test non-existing key") {
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
    }

    def testAddNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID
        Then("Adding a value, the future should fail")
        val e = intercept[UnmodifiableStateException] {
            storage.addValue(classOf[State], id, key, "nonce")
                .await(timeout)
        }

        e.clazz shouldBe classOf[State].getSimpleName
        e.id shouldBe id.toString
        e.key shouldBe key
        e.value shouldBe "nonce"
        e.result shouldBe Code.NONODE.intValue()
    }

    def testGetSingleValueNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID()
        Then("Reading the key, the future should return None")
        storage.getKey(classOf[State], id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)
    }

    def testGetMultiValueNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID()
        Then("Reading the key, the future should return an empty Set")
        storage.getKey(classOf[State], id, key)
            .await(timeout) shouldBe MultiValueKey(key, Set())
    }

    def testRemoveNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID()
        Then("Removing a value, the future should return None")
        storage.removeValue(classOf[State], id, key, null)
            .await(timeout) shouldBe StateResult(NoOwnerId)
    }

    def testAddGetRemoveSingleValue(key: String): Unit = {
        Given("An object in storage")
        val obj = new State
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        storage.create(obj)

        Then("Adding a value should return the current session")
        storage.addValue(classOf[State], obj.id, key, "1")
            .await(timeout) shouldBe StateResult(ownerId)
        And("Reading the key should return the added value")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("1"), ownerId)
        And("Removing the value should return the current session")
        storage.removeValue(classOf[State], obj.id, key, null)
            .await(timeout) shouldBe StateResult(ownerId)
        And("Reading the key again should return no value")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)
    }

    def testAddSingleSameClient(key: String): Unit = {
        Given("An object in storage")
        val obj = new State
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        storage.create(obj)

        Then("Adding a value should return the current session")
        storage.addValue(classOf[State], obj.id, key, "1")
            .await(timeout) shouldBe StateResult(ownerId)
        And("Adding a second value should return the current session")
        storage.addValue(classOf[State], obj.id, key, "2")
            .await(timeout) shouldBe StateResult(ownerId)
        And("Reading the key should return the second value")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("2"), ownerId)
    }

    def testRemoveSingleDifferentClient(key: String): Unit = {
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
        storage.addValue(classOf[State], obj.id, key, "1")
            .await(timeout) shouldBe StateResult(ownerId)
        And("Removing the value from the second client should fail")
        val e = intercept[NotStateOwnerException] {
            storage2.removeValue(classOf[State], obj.id, key, null)
                .await(timeout) shouldBe StateResult(ownerId)
        }

        e.clazz shouldBe classOf[State].getSimpleName
        e.id shouldBe obj.id.toString
        e.key shouldBe key
        e.owner shouldBe ownerId

        curator2.close()
    }

    feature("Test single value with first write wins") {
        scenario("Add for non-existing object") {
            testAddNonExistingObject("first")
        }

        scenario("Get for non-existing object") {
            testGetSingleValueNonExistingObject("first")
        }

        scenario("Remove for non-existing object") {
            testRemoveNonExistingObject("first")
        }

        scenario("Add, get, remove value for object") {
            testAddGetRemoveSingleValue("first")
        }

        scenario("Add another value for the same client") {
            testAddSingleSameClient("first")
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
            val e = intercept[NotStateOwnerException] {
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
            testRemoveSingleDifferentClient("first")
        }
    }

    feature("Test single value with last write wins") {
        scenario("Add for non-existing object") {
            testAddNonExistingObject("last")
        }

        scenario("Get for non-existing object") {
            testGetSingleValueNonExistingObject("last")
        }

        scenario("Remove for non-existing object") {
            testRemoveNonExistingObject("last")
        }

        scenario("Add, get, remove value for object") {
            testAddGetRemoveSingleValue("last")
        }

        scenario("Add another value for the same client") {
            testAddSingleSameClient("last")
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
            val ownerId2 = curator2.getZookeeperClient.getZooKeeper.getSessionId
            val storage2 = new ZookeeperObjectMapper(ZK_ROOT, curator2)
            initAndBuildStorage(storage2)

            Then("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "last", "1")
                .await(timeout) shouldBe StateResult(ownerId)
            And("Adding a value from the second client should fail")
            storage2.addValue(classOf[State], obj.id, "last", "2")
                .await(timeout) shouldBe StateResult(ownerId2)
            And("Reading the key should return the second value")
            storage.getKey(classOf[State], obj.id, "last")
                .await(timeout) shouldBe SingleValueKey("last", Some("2"),
                                                        ownerId2)

            curator2.close()
        }

        scenario("Remove another value for a different client") {
            testRemoveSingleDifferentClient("last")
        }
    }

    feature("Test multiple value") {
        scenario("Add for non-existing object") {
            testAddNonExistingObject("multi")
        }

        scenario("Get for non-existing object") {
            testGetMultiValueNonExistingObject("multi")
        }

        scenario("Remove for non-existing object") {
            testRemoveNonExistingObject("multi")
        }

        scenario("Add, get, remove value for object by single client") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            Then("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(ownerId)
            And("Reading the key should return the added value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("1"))

            When("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(ownerId)
            And("Reading the key should return all added value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("1", "2"))

            When("Removing a value should return the current session")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(ownerId)
            And("Reading the key again should return the other value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("2"))

            When("Removing the other value should return the current session")
            storage.removeValue(classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(ownerId)
            And("Reading the key again should return no value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set())
        }

        scenario("Add, get, remove value for object by multiple clients") {
            Given("An object in storage")
            val obj = new State
            val ownerId1 = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            And("A second storage client")
            val curator2 = CuratorFrameworkFactory.newClient(zk.getConnectString,
                                                             sessionTimeoutMs,
                                                             cnxnTimeoutMs,
                                                             retryPolicy)
            curator2.start()
            if (!curator2.blockUntilConnected(1000, TimeUnit.SECONDS))
                fail("Curator did not connect to the test ZK server")
            val ownerId2 = curator2.getZookeeperClient.getZooKeeper.getSessionId
            val storage2 = new ZookeeperObjectMapper(ZK_ROOT, curator2)
            initAndBuildStorage(storage2)

            When("First client adds a value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(ownerId1)
            Then("Reading the key should return the added value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("1"))

            When("Second client adds a value")
            storage2.addValue(classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(ownerId2)
            Then("Reading the key should return all values")
            storage2.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("1", "2"))

            When("First client removes its value")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(ownerId1)
            Then("Reading the key should return the remaining value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("2"))

            When("Second client removes its value")
            storage2.removeValue(classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(ownerId2)
            Then("Reading the key should return the remaining value")
            storage2.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set())

            curator2.close()
        }

        scenario("A client cannot remove a value for another client") {
            Given("An object in storage")
            val obj = new State
            val ownerId1 = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            And("A second storage client")
            val curator2 = CuratorFrameworkFactory.newClient(zk.getConnectString,
                                                             sessionTimeoutMs,
                                                             cnxnTimeoutMs,
                                                             retryPolicy)
            curator2.start()
            if (!curator2.blockUntilConnected(1000, TimeUnit.SECONDS))
                fail("Curator did not connect to the test ZK server")
            val ownerId2 = curator2.getZookeeperClient.getZooKeeper.getSessionId
            val storage2 = new ZookeeperObjectMapper(ZK_ROOT, curator2)
            initAndBuildStorage(storage2)

            When("First client adds a value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(ownerId1)
            Then("Second client removing the value should fail")
            val e = intercept[NotStateOwnerException] {
                storage2.removeValue(classOf[State], obj.id, "multi", "1")
                    .await(timeout)
            }

            e.clazz shouldBe classOf[State].getSimpleName
            e.id shouldBe obj.id.toString
            e.key shouldBe "multi"
            e.value shouldBe "1"
            e.owner shouldBe ownerId1

            curator2.close()
        }
    }

    feature("Test observables for single value") {
        scenario("Object does not exist") {
            Given("A random object identifier")
            val id = UUID.randomUUID()

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], id, "first")
                .subscribe(obs)

            Then("The observer should complete")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents shouldBe empty
        }

        scenario("Value does not exist") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                   .subscribe(obs)

            Then("The observer receives no value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
        }

        scenario("Value exists in storage") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            Then("The observer receives the value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", Some("1"), ownerId)
        }

        scenario("Value is created") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            Then("The observer receives no value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)

            When("Adding a key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            Then("The observer receives no value")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", Some("1"), ownerId)
        }

        scenario("Value is deleted and recreated") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            And("The value is deleted")
            storage.removeValue(classOf[State], obj.id, "first", null)
                .await(timeout)

            And("A new value is added")
            storage.addValue(classOf[State], obj.id, "first", "2")
                .await(timeout)

            Then("The observer receives all updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", Some("1"), ownerId)
            obs.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
            obs.getOnNextEvents.get(2) shouldBe SingleValueKey(
                "first", Some("2"), ownerId)
        }

        scenario("Observable completes on object deletion") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            And("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The object is deleted")
            storage.delete(classOf[State], obj.id)

            Then("The observable should complete")
            obs.awaitCompletion(1 second)
        }

        scenario("Observable notifies identical values") {
            Given("An object in storage")
            val obj = new State
            val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            And("The same value is added two more times")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            And("Removing the value")
            storage.removeValue(classOf[State], obj.id, "first", null)
                .await(timeout)

            Then("The observer receives all updates")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", Some("1"), ownerId)
            obs.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", Some("1"), ownerId)
            obs.getOnNextEvents.get(2) shouldBe SingleValueKey(
                "first", Some("1"), ownerId)
            obs.getOnNextEvents.get(3) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
        }
    }

    feature("Test observable for multi value") {
        scenario("Object does not exist") {
            Given("A random object identifier")
            val id = UUID.randomUUID()

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], id, "multi")
                .subscribe(obs)

            Then("The observer should complete")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents shouldBe empty
        }

        scenario("Value does not exist") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            Then("The observer receives no value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey("multi", Set())
        }

        scenario("Values exist in storage") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "multi", "2")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "multi", "3")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            Then("The observer receives the value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey(
                "multi", Set("1", "2", "3"))
        }

        scenario("Values are added and deleted") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            And("Another value is added")
            storage.addValue(classOf[State], obj.id, "multi", "2")
                .await(timeout)

            And("The first value is deleted")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            And("The second value is deleted")
            storage.removeValue(classOf[State], obj.id, "multi", "2")
                .await(timeout)

            And("Another value is added")
            storage.addValue(classOf[State], obj.id, "multi", "3")
                .await(timeout)


            Then("The observer receives all updates")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey(
                "multi", Set("1"))
            obs.getOnNextEvents.get(1) shouldBe MultiValueKey(
                "multi", Set("1", "2"))
            obs.getOnNextEvents.get(2) shouldBe MultiValueKey(
                "multi", Set("2"))
            obs.getOnNextEvents.get(3) shouldBe MultiValueKey(
                "multi", Set())
            obs.getOnNextEvents.get(4) shouldBe MultiValueKey(
                "multi", Set("3"))
        }

        scenario("Observable completes on object deletion") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            And("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The object is deleted")
            storage.delete(classOf[State], obj.id)

            Then("The observable should complete")
            obs.awaitCompletion(1 second)
        }

        scenario("Observable does not notify identical values") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            And("The same value is added two more times")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            And("Removing the value")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            Then("The observer receives all updates")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey(
                "multi", Set("1"))
            obs.getOnNextEvents.get(1) shouldBe MultiValueKey(
                "multi", Set())
        }
    }
}
