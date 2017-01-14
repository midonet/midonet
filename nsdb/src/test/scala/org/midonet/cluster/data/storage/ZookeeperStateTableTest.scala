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

import scala.concurrent.Future
import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry

import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.Util
import org.midonet.cluster.backend.Directory
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.data.storage.StorageTestClasses.{PojoBridge, PojoRouter}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.ConnectionState
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent._
import org.midonet.util.functors.makeRunnable

object ZookeeperStateTableTest {
    class ScoreStateTable(val key: StateTable.Key,
                          val directory: Directory,
                          val proxy: StateTableClient,
                          val connection: Observable[ConnectionState],
                          val metrics: StorageMetrics)
        extends StateTable[Int, String] {

        override def start(): Unit = ???
        override def stop(): Unit = ???
        override def add(key: Int, value: String): Unit = ???
        override def addPersistent(key: Int, value: String): Future[Unit] = ???
        override def remove(key: Int): String = ???
        override def remove(key: Int, value: String): Boolean = ???
        override def removePersistent(key: Int, value: String): Future[Boolean] = ???
        override def containsLocal(key: Int): Boolean = ???
        override def containsLocal(key: Int, value: String): Boolean = ???
        override def containsRemote(key: Int): Future[Boolean] = ???
        override def containsRemote(key: Int, value: String): Future[Boolean] = ???
        override def containsPersistent(key: Int, value: String): Future[Boolean] = ???
        override def getLocal(key: Int): String = ???
        override def getLocalByValue(value: String): Set[Int] = ???
        override def getRemote(key: Int): Future[String] = ???
        override def getRemoteByValue(value: String): Future[Set[Int]] = ???
        override def localSnapshot: Map[Int, String] = ???
        override def remoteSnapshot: Future[Map[Int, String]] = ???
        override def observable: Observable[Update[Int, String]] = ???
        override def ready: Observable[StateTable.Key] = ???
        override def isReady: Boolean = ???
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
        new ZookeeperObjectMapper(config, namespaceId, curator, curator,
                                  stateTables, reactor,
                                  new StorageMetrics(new MetricRegistry))
    }

    private def setupStorage(): ZookeeperObjectMapper = {
        val storage = newStorage()
        storage.registerClass(classOf[PojoBridge])
        storage.registerTable(classOf[PojoBridge], classOf[Int], classOf[String],
                              "score-table", classOf[ScoreStateTable])
        storage.registerTable(classOf[Int], classOf[String], "global-table",
                              classOf[ScoreStateTable])
        storage.build()
        storage
    }

    feature("Input validation") {
        scenario("Cannot register a global state table after the storage was built") {
            val storage = setupStorage()
            intercept[IllegalStateException] {
                storage.registerTable(classOf[PojoBridge], classOf[Int],
                                      classOf[String], "another-global-table",
                                      classOf[ScoreStateTable])
            }
        }

        scenario("Cannot register a global state table for a different key") {
            val storage = newStorage()
            storage.registerTable(classOf[Int], classOf[String], "same-table",
                                  classOf[ScoreStateTable])
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[String], classOf[String],
                                      "same-table", classOf[OtherKeyStateTable])
            }
        }

        scenario("Cannot register a global state table for a different value") {
            val storage = newStorage()
            storage.registerTable(classOf[Int], classOf[String], "same-table",
                                  classOf[ScoreStateTable])
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[Int], classOf[Int], "same-table",
                                      classOf[OtherValueStateTable])
            }
        }

        scenario("Cannot register a global state table for a different provider") {
            val storage = newStorage()
            storage.registerTable(classOf[Int], classOf[String], "same-table",
                                  classOf[ScoreStateTable])
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[Int], classOf[String], "same-table",
                                      classOf[OtherStateTable])
            }
        }

        scenario("Cannot register a class state table after the storage was built") {
            val storage = setupStorage()
            intercept[IllegalStateException] {
                storage.registerTable(classOf[PojoBridge], classOf[Int],
                                      classOf[String], "another-table",
                                      classOf[ScoreStateTable])
            }
        }

        scenario("Cannot register a class state table for a non-registered class") {
            val storage = newStorage()
            intercept[IllegalArgumentException] {
                storage.registerTable(classOf[PojoRouter], classOf[Int],
                                      classOf[String], "another-table",
                                      classOf[ScoreStateTable])
            }
        }

        scenario("Cannot register a class state table for a different key") {
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

        scenario("Cannot register a class state table for a different value") {
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

        scenario("Cannot register a class state table for a different provider") {
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
        scenario("Paths are correct") {
            Given("State table storage")
            val storage = setupStorage()

            Then("The paths are correct")
            val version = storage.version.get
            val id = UUID.randomUUID()
            storage.tablesPath() shouldBe s"$zkRoot/zoom/$version/tables"
            storage.tablesGlobalPath() shouldBe
                s"$zkRoot/zoom/$version/tables/global"
            storage.tablesClassPath(classOf[PojoBridge]) shouldBe
                s"$zkRoot/zoom/$version/tables/PojoBridge"
            storage.tablesObjectPath(classOf[PojoBridge], id) shouldBe
                s"$zkRoot/zoom/$version/tables/PojoBridge/$id"
            storage.tableRootPath(classOf[PojoBridge], id, "name") shouldBe
                s"$zkRoot/zoom/$version/tables/PojoBridge/$id/name"
            storage.tablePath(classOf[PojoBridge], id, "name") shouldBe
                s"$zkRoot/zoom/$version/tables/PojoBridge/$id/name"
            storage.tablePath(classOf[PojoBridge], id, "name", version, 0) shouldBe
                s"$zkRoot/zoom/$version/tables/PojoBridge/$id/name/0"
            storage.tablePath(classOf[PojoBridge], id, "name", version, 0, 1) shouldBe
                s"$zkRoot/zoom/$version/tables/PojoBridge/$id/name/0/1"
            storage.tablePath("name", version) shouldBe
                s"$zkRoot/zoom/$version/tables/global/name"

            And("The legacy paths are correct for a network")
            storage.legacyTablePath(classOf[Network], id, "mac_table") shouldBe
                Some(s"$zkRoot/bridges/$id/mac_ports")
            storage.legacyTablePath(classOf[Network], id, "mac_table", 1) shouldBe
                Some(s"$zkRoot/bridges/$id/vlans/1/mac_ports")
            storage.legacyTablePath(classOf[Network], id, "ip4_mac_table") shouldBe
                Some(s"$zkRoot/bridges/$id/ip4_mac_map")
        }

        scenario("Class path exists") {
            Given("The path for a registered class")
            val storage = setupStorage()
            val path = storage.tablesClassPath(classOf[PojoBridge])
            Then("The path should exist")
            curator.checkExists().forPath(path) should not be null
        }

        scenario("Global state table path created with storage") {
            Given("A storage")
            val storage = setupStorage()

            And("A path for global tables")
            val path = storage.tablesGlobalPath()

            Then("Tables path created with storage")
            curator.checkExists().forPath(path) should not be null
        }

        scenario("Global table path created with storage") {
            Given("A storage")
            val storage = setupStorage()

            And("A path for global tables")
            val path = storage.tablePath("global-table")

            Then("Tables path created with storage")
            curator.checkExists().forPath(path) should not be null
        }

        scenario("Global table can be retrieved from storage") {
            Given("A storage")
            val storage = setupStorage()

            And("A path for global table 'global-table'")
            val path = storage.tablePath("global-table")

            Then("getTable() does not throw anything")
            val t = storage.getTable[Int, String]("global-table")
            t.isInstanceOf[ScoreStateTable] shouldBe true
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

            And("The table arguments should not be null")
            val scoreTable = table.asInstanceOf[ScoreStateTable]
            scoreTable.key shouldBe StateTable.Key(
                classOf[PojoBridge], obj.id, classOf[Int], classOf[String],
                "score-table", Seq.empty)

            scoreTable.directory should not be null
            scoreTable.proxy should not be null
            scoreTable.connection should not be null

            And("The table directory path should be the table path")
            val path = storage.tableRootPath(classOf[PojoBridge], obj.id,
                                             "score-table")
            scoreTable.directory.getPath shouldBe path
        }

        scenario("Storage caches state table instances") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            And("Multiple threads requesting the table")
            val tables = new Array[StateTable[Int, String]](10)
            val threads = for (index <- 0 until 10) yield {
                new Thread(makeRunnable {
                    tables(index) =
                        storage.getTable[Int, String](classOf[PojoBridge],
                                                      obj.id, "score-table")
                })
            }

            When("All threads are requesting the table")
            for (index <- 0 until 10) {
                threads(index).start()
            }

            And("Waiting for the threads to complete")
            for (index <- 0 until 10) {
                threads(index).join()
            }

            And("Synchronizing access to the results with a barrier")
            Util.getUnsafe.fullFence()

            Then("All threads should receive the same table instance")
            for (index <- 1 until 10) {
                (tables(0) eq tables(index)) shouldBe true
            }
        }

        scenario("Storage removes cached table on object deletion") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            When("Retrieving the table")
            val table1 = storage.getTable[Int, String](classOf[PojoBridge],
                                                       obj.id, "score-table")

            Then("The table should not be null")
            table1 should not be null

            When("Deleting the object")
            storage.delete(classOf[PojoBridge], obj.id)

            And("Recreating the object")
            eventually {
                storage.create(obj)
            }

            When("Retrieving the table")
            eventually {
                val table2 = storage.getTable[Int, String](classOf[PojoBridge],
                                                           obj.id,
                                                           "score-table")

                Then("The table should be a different instance")
                (table1 ne table2) shouldBe true
            }
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

    feature("Test state table arguments") {
        scenario("Storage returns arguments for tables") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            And("Two tables with arguments")
            storage.getTable[Int, String](classOf[PojoBridge], obj.id,
                                          "score-table", 0)
            storage.getTable[Int, String](classOf[PojoBridge], obj.id,
                                          "score-table", 1)

            When("Retrieving the arguments")
            val args = storage.tableArguments(classOf[PojoBridge], obj.id,
                                              "score-table").await(timeout)

            Then("The arguments should be 0 and 1")
            args shouldBe Set("0", "1")
        }

        scenario("Storage returns empty arguments for non-existing tables") {
            Given("An object in storage")
            val storage = setupStorage()
            val obj = new PojoBridge(UUID.randomUUID(), null, null, null)
            storage.create(obj)

            When("Retrieving the arguments")
            val args = storage.tableArguments(classOf[PojoBridge], obj.id,
                                              "score-table", 0).await(timeout)

            Then("The arguments should be empty")
            args shouldBe empty
        }
    }

}
