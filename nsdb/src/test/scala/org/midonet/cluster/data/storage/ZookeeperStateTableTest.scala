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

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

import com.google.inject.Inject

import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{GivenWhenThen, Matchers, FeatureSpec}
import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.data.storage.StorageTestClasses.{PojoRouter, PojoBridge}
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.util.MidonetEventually

object ZookeeperStateTableTest {
    private class ScoreStateTable @Inject()(val directory: Directory,
                                            val connectionWatcher: ZkConnectionAwareWatcher)
        extends StateTable[Int, String] {

        override def start(): Unit = ???
        override def stop(): Unit = ???
        override def add(key: Int, value: String): Unit = ???
        override def addPersistent(key: Int, value: String): Unit = ???
        override def remove(key: Int): String = ???
        override def remove(key: Int, value: String): String = ???
        override def removePersistent(key: Int, value: String): String = ???
        override def containsLocal(key: Int): Boolean = ???
        override def containsLocal(key: Int, value: String): Boolean = ???
        override def containsRemote(key: Int): Boolean = ???
        override def containsRemote(key: Int, value: String): Boolean = ???
        override def containsPersistent(key: Int, value: String): Boolean = ???
        override def getLocal(key: Int): String = ???
        override def getLocalByValue(value: String): Set[Int] = ???
        override def getRemote(key: Int): String = ???
        override def getRemoteByValue(value: String): Set[Int] = ???
        override def localSnapshot: Map[Int, String] = ???
        override def remoteSnapshot: Map[Int, String] = ???
        override def observable: Observable[Update[Int, String]] = ???
    }
}

@RunWith(classOf[JUnitRunner])
class ZookeeperStateTableTest extends FeatureSpec with MidonetBackendTest
                              with Matchers with GivenWhenThen
                              with MidonetEventually {

    import ZookeeperStateTableTest.ScoreStateTable

    private abstract class OtherStateTable extends StateTable[Int, String]
    private abstract class OtherKeyStateTable extends StateTable[String, String]
    private abstract class OtherValueStateTable extends StateTable[Int, Int]

    private val namespaceId = UUID.randomUUID.toString
    private final val timeout = 5 seconds

    private def newStorage(): ZookeeperObjectMapper = {
        new ZookeeperObjectMapper(zkRoot, namespaceId, curator, reactor,
                                  connection, connectionWatcher)
    }

    private def setupStorage(): ZookeeperObjectMapper = {
        val storage = newStorage()
        storage.registerClass(classOf[PojoBridge])
        storage.registerTable(classOf[PojoBridge], classOf[Int], classOf[String],
                              "score-table", classOf[ScoreStateTable])
        storage.build()
        storage
    }

    feature("Input validation") {
        scenario("Cannot register a state table after the storage was built") {
            val storage = setupStorage()
            intercept[IllegalStateException] {
                storage.registerTable(classOf[PojoBridge], classOf[Int],
                                      classOf[String], "another-table",
                                      classOf[ScoreStateTable])
            }
        }

        scenario("Cannot register a state table for a non-registered class") {
            val storage = newStorage()
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[PojoRouter], classOf[Int],
                                      classOf[String], "another-table",
                                      classOf[ScoreStateTable])
            }
        }

        scenario("Cannot register a state table for a different key") {
            val storage = newStorage()
            storage.registerClass(classOf[PojoBridge])
            storage.registerTable(classOf[PojoBridge], classOf[Int],
                                  classOf[String], "same-table",
                                  classOf[ScoreStateTable])
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[PojoBridge], classOf[String],
                                      classOf[String], "same-table",
                                      classOf[OtherKeyStateTable])
            }
        }

        scenario("Cannot register a state table for a different value") {
            val storage = newStorage()
            storage.registerClass(classOf[PojoBridge])
            storage.registerTable(classOf[PojoBridge], classOf[Int],
                                  classOf[String], "same-table",
                                  classOf[ScoreStateTable])
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[PojoBridge], classOf[Int],
                                      classOf[Int], "same-table",
                                      classOf[OtherValueStateTable])
            }
        }

        scenario("Cannot register a state table for a different provider") {
            val storage = newStorage()
            storage.registerClass(classOf[PojoBridge])
            storage.registerTable(classOf[PojoBridge], classOf[Int],
                                  classOf[String], "same-table",
                                  classOf[ScoreStateTable])
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[PojoBridge], classOf[Int],
                                      classOf[String], "same-table",
                                      classOf[OtherStateTable])
            }
        }

        scenario("Cannot get a table if the storage was not built") {
            val storage = newStorage()
            intercept[ServiceUnavailableException] {
                storage.getTable[Int, String](classOf[PojoBridge], UUID.randomUUID(),
                                              "score-table")
            }
        }
    }

    feature("Test state table paths") {
        scenario("Class path exists") {
            Given("The path for a registered class")
            val storage = setupStorage()
            val path = storage.tablesClassPath(classOf[PojoBridge])
            Then("The path should exist")
            curator.checkExists().forPath(path) should not be null
        }

        scenario("Table path created with object") {
            Given("An object")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)

            And("The path for the table of this object")
            val path = storage.tableRootPath(classOf[PojoBridge], obj.id,
                                             "score-table")

            When("The object is created")
            storage.create(obj)

            Then("The path should exist")
            curator.checkExists().forPath(path) should not be null
        }

        scenario("Table path deleted with object") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            And("The path for the table of this object")
            val path = storage.tableRootPath(classOf[PojoBridge], obj.id,
                                             "score-table")

            When("The object is deleted")
            storage.delete(classOf[PojoBridge], obj.id)

            Then("The path should not exist")
            eventually {
                curator.checkExists().forPath(path) shouldBe null
            }
        }
    }

    feature("Test state table") {
        scenario("Fail to create a state table for non-existing class") {
            val storage = setupStorage()
            intercept[IllegalArgumentException] {
                storage.getTable[Int, String](classOf[PojoRouter],
                                              UUID.randomUUID(), "some-table")
            }
        }

        scenario("Fail to create a state table for non-existing object") {
            val storage = setupStorage()
            intercept[IllegalArgumentException] {
                storage.getTable[Int, String](classOf[PojoBridge],
                                              UUID.randomUUID(), "some-table")
            }
        }

        scenario("Fail to create a state table for non-existing name") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            Then("Retrieving the table with non-existing name should fail")
            intercept[IllegalArgumentException] {
                storage.getTable[Int, String](classOf[PojoBridge], obj.id,
                                              "some-table")
            }
        }

        scenario("Fail to create a state table for wrong key type") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            Then("Retrieving the table for wrong key type should fail")
            intercept[IllegalArgumentException] {
                storage.getTable[String, String](classOf[PojoBridge], obj.id,
                                                 "score-table")
            }
        }

        scenario("Fail to create a state table for wrong value type") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            Then("Retrieving the table for wrong value type should fail")
            intercept[IllegalArgumentException] {
                storage.getTable[Int, Int](classOf[PojoBridge], obj.id,
                                           "score-table")
            }
        }

        scenario("Storage creates a state table instance") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            When("Retrieving the table")
            val table = storage.getTable[Int, String](classOf[PojoBridge],
                                                      obj.id, "score-table")

            Then("The table should not be null")
            table should not be null

            And("The table connection watcher should not be null")
            val scoreTable = table.asInstanceOf[ScoreStateTable]
            scoreTable.connectionWatcher shouldBe connectionWatcher

            And("The table directory path should be the table path")
            val path = storage.tableRootPath(classOf[PojoBridge], obj.id,
                                             "score-table")
            scoreTable.directory.getPath shouldBe path
        }

        scenario("State table directory writes to storage") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            When("Retrieving the table")
            val table = storage.getTable[Int, String](classOf[PojoBridge],
                                                      obj.id, "score-table")
            val scoreTable = table.asInstanceOf[ScoreStateTable]

            And("The table directory adds a node to storage")
            scoreTable.directory.add("/test_node", null, CreateMode.PERSISTENT)

            Then("The node should exist on the state table path")
            val path = storage.tableRootPath(classOf[PojoBridge], obj.id,
                                             "score-table")
            curator.checkExists().forPath(path + "/test_node") should not be null
        }
    }

}
