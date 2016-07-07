/*
 * Copyright 2016 Midokura SARL
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

import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{StateAccessException, ZkDirectory}
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class DirectoryStateTableTest extends FlatSpec with BeforeAndAfter
                              with Matchers with MidonetBackendTest
                              with GivenWhenThen {

    private var path: String = _

    class TestStateTable(override val directory: Directory)
        extends DirectoryStateTable[String, String] {
        override def start(): Unit = {}
        override def stop(): Unit = {}
        override def add(key: String, value: String): Unit = {}
        override def remove(key: String): String = null
        override def remove(key: String, value: String): Boolean = false
        override def containsLocal(key: String): Boolean = false
        override def containsLocal(key: String, value: String): Boolean = false
        override def getLocal(key: String): String = null
        override def getLocalByValue(value: String): Set[String] = Set.empty
        override def localSnapshot: Map[String, String] = Map.empty
        override def observable: Observable[Update[String, String]] =
            Observable.never()

        protected override def decodeKey(string: String): String = string
        protected override def decodeValue(string: String): String = string
        protected override def encodeKey(string: String): String = string
        protected override def encodeValue(string: String): String = string
        protected override def decodeKey(kv: KeyValue): String =
            kv.getDataVariable.toStringUtf8
        protected override def decodeValue(kv: KeyValue): String =
            kv.getDataVariable.toStringUtf8
        protected override val nullValue: String = null
    }

    override def beforeEach(): Unit = {
        super.beforeEach()
        path = s"$zkRoot/directory_state_table"
        ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, path)
        ZKPaths.deleteChildren(curator.getZookeeperClient.getZooKeeper, path,
                               false)
    }

    "Directory state table" should "support persistent operations" in {
        Given("A directory state table")
        val table = new TestStateTable(new ZkDirectory(connection, path, reactor))

        Then("The remote snapshot should be empty")
        table.remoteSnapshot.await() shouldBe empty

        When("Adding a persistent value")
        table.addPersistent("key0", "value0").await()

        Then("The remote snapshot should contain the value")
        table.remoteSnapshot.await() should contain only "key0" -> "value0"

        And("Zookeeper should contain the node")
        curator.getChildren.forPath(path) should contain only
            s"key0,value0,${StateTableEncoder.PersistentVersion}"

        And("The table should indicates that contains the key")
        table.containsRemote("key0").await() shouldBe true
        table.containsRemote("key0", "value0").await() shouldBe true
        table.containsPersistent("key0", "value0").await() shouldBe true

        And("The table should not indicate that contains other key/values")
        table.containsRemote("key1").await() shouldBe false
        table.containsRemote("key0", "value1").await() shouldBe false
        table.containsPersistent("key0", "value1").await() shouldBe false

        And("The table should return the value for the key")
        table.getRemote("key0").await() shouldBe "value0"
        table.getRemote("key1").await() shouldBe null

        And("The table should return the key set for the value")
        table.getRemoteByValue("value0").await() shouldBe Set("key0")
        table.getRemoteByValue("value1").await() shouldBe Set.empty

        When("Removing the persistent value")
        table.removePersistent("key0", "value0").await() shouldBe true

        Then("The remote snapshot should be empty")
        table.remoteSnapshot.await() shouldBe empty

        And("Zookeeper should not contain the node")
        curator.getChildren.forPath(path) shouldBe empty

        And("The table should indicates that it does not contain the key")
        table.containsRemote("key0").await() shouldBe false
        table.containsRemote("key0", "value0").await() shouldBe false
        table.containsPersistent("key0", "value0").await() shouldBe false

        And("The table should not return the value for the key")
        table.getRemote("key0").await() shouldBe null

        And("The table should return an empty set for the value")
        table.getRemoteByValue("value0").await() shouldBe Set.empty
    }

    "Directory state table" should "handle multiple persistent entries" in {
        Given("A directory state table")
        val table = new TestStateTable(new ZkDirectory(connection, path, reactor))

        When("Adding two values for a key")
        table.addPersistent("key0", "value0").await()
        table.addPersistent("key0", "value1").await()

        Then("The remote snapshot should contain the value")
        table.remoteSnapshot.await() should contain only "key0" -> "value0"

        And("Zookeeper should contain the node")
        curator.getChildren.forPath(path) should contain allOf (
            s"key0,value0,${StateTableEncoder.PersistentVersion}",
            s"key0,value1,${StateTableEncoder.PersistentVersion}"
            )

        And("The table should indicates that contains the key")
        table.containsRemote("key0").await() shouldBe true
        table.containsRemote("key0", "value0").await() shouldBe true
        table.containsRemote("key0", "value1").await() shouldBe true
        table.containsPersistent("key0", "value0").await() shouldBe true
        table.containsPersistent("key0", "value1").await() shouldBe true

        And("The table should not indicate that contains other key/values")
        table.containsRemote("key1").await() shouldBe false
        table.containsRemote("key0", "value2").await() shouldBe false
        table.containsPersistent("key0", "value2").await() shouldBe false

        And("The table should return the value for the key")
        table.getRemote("key0").await() shouldBe "value0"
        table.getRemote("key1").await() shouldBe null

        And("The table should return the key set for the value")
        table.getRemoteByValue("value0").await() shouldBe Set("key0")
        table.getRemoteByValue("value1").await() shouldBe Set("key0")
        table.getRemoteByValue("value2").await() shouldBe Set.empty
    }

    "Directory state table" should "handle non-existent entries" in {
        Given("A directory state table")
        val table = new TestStateTable(new ZkDirectory(connection, path, reactor))

        Then("Removing a non-existent entry should return false")
        table.removePersistent("keyN", "valueN").await() shouldBe false
    }

    "Directory state table" should "handle persistent with learned entries" in {
        Given("A directory state table")
        val directory = new ZkDirectory(connection, path, reactor)
        val table = new TestStateTable(directory)

        When("Adding a persistent entry")
        table.addPersistent("key0", "value0").await()

        And("Adding a learned entry for the same key")
        directory.add("/key0,value1,0", null, CreateMode.EPHEMERAL)

        Then("The remote snapshot should contain the learned value")
        table.remoteSnapshot.await() should contain only "key0" -> "value1"

        And("The table should contain the remote value")
        table.containsRemote("key0").await() shouldBe true
        table.containsRemote("key0", "value1").await() shouldBe true
        table.containsPersistent("key0", "value0").await() shouldBe true

        When("Removing the learned entry")
        directory.delete("/key0,value1,0")

        Then("The remote snapshot should contain the persistent value")
        table.remoteSnapshot.await() should contain only "key0" -> "value0"

        And("The table should contain the remote value")
        table.containsRemote("key0").await() shouldBe true
        table.containsRemote("key0", "value0").await() shouldBe true
        table.containsRemote("key0", "value1").await() shouldBe false
        table.containsPersistent("key0", "value0").await() shouldBe true
    }

    "Directory state table" should "handle mixed learned entries" in {
        Given("A directory state table")
        val directory = new ZkDirectory(connection, path, reactor)
        val table = new TestStateTable(directory)

        When("Adding a learned entry for a new key")
        directory.add("/key2,value2,0", null, CreateMode.EPHEMERAL)

        Then("The remote snapshot should contain the learned value")
        table.remoteSnapshot.await() should contain only "key2" -> "value2"

        And("The table should contain the remote value")
        table.containsRemote("key2").await() shouldBe true
        table.containsRemote("key2", "value2").await() shouldBe true
        table.containsPersistent("key2", "value2").await() shouldBe false

        When("Adding a second learned entry for the same key")
        directory.add("/key2,value3,1", null, CreateMode.EPHEMERAL)

        Then("The remote snapshot should contain the learned value")
        table.remoteSnapshot.await() should contain only "key2" -> "value3"

        And("The table should contain the remote value")
        table.containsRemote("key2").await() shouldBe true
        table.containsRemote("key2", "value2").await() shouldBe false
        table.containsRemote("key2", "value3").await() shouldBe true
        table.containsPersistent("key2", "value3").await() shouldBe false
    }

    "Directory state table" should "ignore invalid entries" in {
        Given("A directory state table")
        val directory = new ZkDirectory(connection, path, reactor)
        val table = new TestStateTable(directory)

        When("Adding an invalid entry")
        directory.add("/invalid-entry", null, CreateMode.EPHEMERAL)

        Then("The remote snapshot should be empty")
        table.remoteSnapshot.await() shouldBe empty
    }

    "Directory state table" should "throw on invalid directory" in {
        Given("A directory state table")
        val directory = new ZkDirectory(connection, "/invalid-path", reactor)
        val table = new TestStateTable(directory)

        Then("The table operations should fail")
        intercept[StateAccessException] {
            table.remoteSnapshot.await() shouldBe empty
        }
    }

}
