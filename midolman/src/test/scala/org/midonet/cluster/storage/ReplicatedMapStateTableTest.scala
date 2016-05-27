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

package org.midonet.cluster.storage

import scala.concurrent.duration._

import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.ZkDirectory
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.midolman.state.ReplicatedMap
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class ReplicatedMapStateTableTest extends FlatSpec with GivenWhenThen
                                  with Matchers with MidonetBackendTest
                                  with MidonetEventually {

    private var path: String = _
    private val timeout = 5 seconds

    class TestStateTable(directory: Directory)
        extends ReplicatedMapStateTable[String, String] {

        protected override val map = new ReplicatedMap[String, String](directory) {
            protected override def decodeKey(string: String): String = string
            protected override def decodeValue(string: String): String = string
            protected override def encodeKey(string: String): String = string
            protected override def encodeValue(string: String): String = string
        }
        protected override val nullValue: String = null

        override def addPersistent(key: String, value: String): Unit = { }
        override def containsPersistent(key: String, value: String): Boolean = false
        override def containsRemote(key: String): Boolean = false
        override def containsRemote(key: String, value: String): Boolean = false
        override def getRemote(key: String): String = null
        override def getRemoteByValue(value: String): Set[String] = Set.empty
        override def remoteSnapshot: Map[String, String] = Map.empty
        override def removePersistent(key: String, value: String): Boolean = false
    }

    override def beforeEach(): Unit = {
        super.beforeEach()
        path = s"$zkRoot/directory_state_table"
        ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, path)
        ZKPaths.deleteChildren(curator.getZookeeperClient.getZooKeeper, path,
                               false)
    }

    "State table" should "support learned CRUD operations" in {
        Given("A replicated map state table")
        val table = new TestStateTable(new ZkDirectory(connection, path, reactor))

        When("Starting synchronizing the table")
        table.start()

        Then("The local snapshot should be empty")
        table.localSnapshot shouldBe empty

        When("Adding a learned value")
        table.add("key0", "value0")

        Then("Eventually the snapshot should contain the value")
        eventually { table.localSnapshot should contain only "key0" -> "value0" }

        And("Zookeeper should contain the learned entry")
        curator.getChildren.forPath(path) should contain only
            "key0,value0,0000000000"

        And("The table should contain the key")
        table.containsLocal("key0") shouldBe true
        table.containsLocal("key0", "value0") shouldBe true

        And("The table should not contain other key/values")
        table.containsLocal("key1") shouldBe false
        table.containsLocal("key0", "value1") shouldBe false

        And("The table should return the value for the key")
        table.getLocal("key0") shouldBe "value0"
        table.getLocal("key1") shouldBe null

        And("The table should return the key set for the value")
        table.getLocalByValue("value0") shouldBe Set("key0")
        table.getLocalByValue("value1") shouldBe Set.empty

        When("Adding a second learned value")
        table.add("key0", "value1")

        Then("Eventually the snapshot should contain the value")
        eventually { table.localSnapshot should contain only "key0" -> "value1" }

        And("Zookeeper should contain the learned entry")
        curator.getChildren.forPath(path) should contain only
            "key0,value1,0000000001"

        And("The table should contain the key")
        table.containsLocal("key0") shouldBe true
        table.containsLocal("key0", "value1") shouldBe true

        And("The table should not contain other key/values")
        table.containsLocal("key1") shouldBe false
        table.containsLocal("key0", "value0") shouldBe false

        And("The table should return the value for the key")
        table.getLocal("key0") shouldBe "value1"
        table.getLocal("key1") shouldBe null

        And("The table should return the key set for the value")
        table.getLocalByValue("value1") shouldBe Set("key0")
        table.getLocalByValue("value0") shouldBe Set.empty

        When("Removing the learned value")
        table.remove("key0")

        Then("Eventually the snapshot should not contain the value")
        eventually { table.localSnapshot shouldBe empty }

        And("Zookeeper should not contain the learned entry")
        curator.getChildren.forPath(path) shouldBe empty

        And("The table should not contain the key")
        table.containsLocal("key0") shouldBe false
        table.containsLocal("key0", "value1") shouldBe false
        table.getLocal("key0") shouldBe null

        And("The table should return the key set for the value")
        table.getLocalByValue("value1") shouldBe Set.empty

        table.stop()
    }

    "State table" should "support remove by value" in {
        Given("A replicated map state table")
        val table = new TestStateTable(new ZkDirectory(connection, path, reactor))

        When("Starting synchronizing the table")
        table.start()

        Then("The local snapshot should be empty")
        table.localSnapshot shouldBe empty

        When("Adding a learned value")
        table.add("key0", "value0")

        Then("Eventually the snapshot should contain the value")
        eventually { table.localSnapshot should contain only "key0" -> "value0" }

        When("Removing the key for a different value")
        table.remove("key0", "value1")

        And("A different key")
        table.add("key1", "value1")

        Then("Eventually the snapshot should contain both value")
        eventually {
            table.localSnapshot should contain allOf (
                "key0" -> "value0", "key1" -> "value1")
        }

        When("Removing the correct value")
        table.remove("key0", "value0")

        Then("Eventually the snapshot should contain only the second value")
        eventually { table.localSnapshot should contain only "key1" -> "value1" }

        table.stop()
    }

    "State table" should "handle multiple opinions" in {
        Given("A replicated map state table")
        val directory = new ZkDirectory(connection, path, reactor)
        val table = new TestStateTable(directory)

        When("Starting synchronizing the table")
        table.start()

        Then("The local snapshot should be empty")
        table.localSnapshot shouldBe empty

        When("Adding a learned value")
        table.add("key0", "value0")

        Then("Eventually the snapshot should contain the value")
        eventually { table.localSnapshot should contain only "key0" -> "value0" }

        When("Adding another opinion for the key")
        directory.add("/key0,value1,", null, CreateMode.EPHEMERAL_SEQUENTIAL)

        Then("Eventually the snapshot should contain the second value")
        eventually { table.localSnapshot should contain only "key0" -> "value1" }

        When("Adding another key")
        directory.add("/key2,value2,", null, CreateMode.EPHEMERAL_SEQUENTIAL)

        Then("Eventually the snapshot should contain the second key")
        eventually {
            table.localSnapshot should contain allOf(
                "key0" -> "value1", "key2" -> "value2")
        }

        table.stop()
    }

    "State table" should "handle subscriptions" in {
        Given("A replicated map state table")
        val table = new TestStateTable(new ZkDirectory(connection, path, reactor))

        And("An observable")
        val observer1 = new TestAwaitableObserver[Update[String, String]]

        When("Subscribing the table")
        val sub1 = table.observable.subscribe(observer1)

        And("Adding a value")
        table.add("key0", "value0")

        Then("The observer should receive an update")
        observer1.awaitOnNext(1, timeout) shouldBe true
        observer1.getOnNextEvents.get(0) shouldBe Update("key0", null, "value0")

        Given("A second observer")
        val observer2 = new TestAwaitableObserver[Update[String, String]]

        When("Subscribing the table")
        val sub2 = table.observable.subscribe(observer2)

        And("Adding another value")
        table.add("key1", "value1")

        Then("Both observers should receive an update")
        observer1.awaitOnNext(2, timeout) shouldBe true
        observer1.getOnNextEvents.get(1) shouldBe Update("key1", null, "value1")
        observer2.awaitOnNext(1, timeout) shouldBe true
        observer2.getOnNextEvents.get(0) shouldBe Update("key1", null, "value1")

        When("Updating the first key")
        table.add("key0", "value2")

        Then("Both observers should receive an update")
        observer1.awaitOnNext(3, timeout) shouldBe true
        observer1.getOnNextEvents.get(2) shouldBe Update("key0", "value0", "value2")
        observer2.awaitOnNext(2, timeout) shouldBe true
        observer2.getOnNextEvents.get(1) shouldBe Update("key0", "value0", "value2")

        When("The first observer unsubscribes")
        sub1.unsubscribe()

        And("Updating the second key")
        table.add("key1", "value3")

        Then("The second observer should receive an update")
        observer2.awaitOnNext(3, timeout) shouldBe true
        observer2.getOnNextEvents.get(2) shouldBe Update("key1", "value1", "value3")

        sub2.unsubscribe()
    }

}
