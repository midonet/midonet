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

package org.midonet.brain

import java.io.PrintWriter
import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

import scala.util.{Random, Try}

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
=======
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
import org.slf4j.LoggerFactory

import org.midonet.brain.ClusterNode.Context
import org.midonet.brain.services.c3po.{C3POConfig, C3POMinion}
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, NoData}
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.data.neutron.{NeutronResourceType, TaskType}
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.C3PO
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.storage.ZoomProvider
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps

/** Tests the service that synces the Neutron DB into Midonet's backend. */
@RunWith(classOf[JUnitRunner])
<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
class C3PODaemonTest extends FlatSpec with BeforeAndAfter
                                      with BeforeAndAfterAll
                                      with Matchers {
=======
class C3POMinionTest extends FlatSpec with BeforeAndAfter with Matchers {

>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
    private val log = LoggerFactory.getLogger(this.getClass)

    private val ZK_ROOT = "/midonet-test"
    private val ZK_PORT = 50000 + Random.nextInt(15000)
    private val ZK_HOST = s"127.0.0.1:$ZK_PORT"

    private val DB_NAME = "taskdb"
    private val DB_CONNECT_STR = s"jdbc:sqlite:file:$DB_NAME?mode=memory&cache=shared"
    private val DB_DRIVER = "org.sqlite.JDBC"

    private val DROP_TASK_TABLE = "DROP TABLE IF EXISTS midonet_tasks"
    private val TRUNCATE_TASK_TABLE = "DELETE FROM midonet_tasks"
    private val CREATE_TASK_TABLE =
        "CREATE TABLE midonet_tasks (" +
        "    id int(11) NOT NULL," +
        "    type_id int(11) NOT NULL," +
        "    data_type_id int(11) DEFAULT NULL," +
        "    data longtext," +
        "    resource_id varchar(36) DEFAULT NULL," +
        "    transaction_id varchar(40) NOT NULL," +
        "    created_at datetime NOT NULL," +
        "    PRIMARY KEY (id)" +
        ")"

    private val c3poCfg = new C3POConfig {
        override def periodMs: Long = 1000
        override def delayMs: Long = 0
        override def isEnabled: Boolean = true
        override def minionClass: String = classOf[C3PO].getName
        override def numThreads: Int = 1
    }

    // Data sources
    private val zk: TestingServer = new TestingServer(ZK_PORT)

    // Adapt the DriverManager interface to DataSource interface.
    // SQLite doesn't seem to provide JDBC 2.0 API.
    private val dataSrc = new DataSource() {
        override def getConnection() = DriverManager.getConnection(DB_CONNECT_STR)
        override def getConnection(username: String, password: String) = null
        override def getLoginTimeout = -1
        override def getLogWriter = null
        override def setLoginTimeout(seconds: Int) {}
        override def setLogWriter(out: PrintWriter) {}
        override def getParentLogger = null
        override def isWrapperFor(clazz: Class[_]) = false
        override def unwrap[T](x: Class[T]): T = null.asInstanceOf[T]
    }

    // We need to keep one connection open to maintain the shared in-memory DB
    // during the test.
    private val dummyConnection = dataSrc.getConnection()

    // ---------------------
    // DATA FIXTURES
    // ---------------------

    private def executeSqlStmts(sqls: String*) {
        var c: Connection = null
        try {
            c = dataSrc.getConnection()
            val stmt = c.createStatement()
            sqls.foreach { sql => stmt.executeUpdate(sql) }
            stmt.close()
        } finally {
            if (c != null) c.close()
        }
    }

    private def createTaskTable() = {
        // Just in case an old DB file / table exits.
        executeSqlStmts(DROP_TASK_TABLE)
        executeSqlStmts(CREATE_TASK_TABLE)
        log.info("Created the midonet_tasks table.")
    }

    def emptyTaskTableAndSendFlushTask() = {
        executeSqlStmts(EMPTY_TASK_TABLE)
        log.info("Emptied the task table.")

        // A flush task must have an id of 1 by spec.
        executeSqlStmts(insertMidoNetTaskSql(
                id = 1, Flush, NoData, json = "", null, "flush_txn"))
        log.info("Inserted a flush task.")
        Thread.sleep(1000)
    }

    private def insertMidoNetTaskSql(
            id: Int, taskType: TaskType, dataType: NeutronResourceType[_],
            json: String, resourceId: UUID, txnId: String) : String = {
        val rsrcIdStr = if (resourceId != null) s"'$resourceId'"
                        else "NULL"
        "INSERT INTO midonet_tasks values(" +
        s"$id, ${taskType.id}, ${dataType.id},'$json', $rsrcIdStr, '$txnId', " +
        "datetime('now'))"
    }

    val network1Uuid = UUID.fromString("d32019d3-bc6e-4319-9c1d-6722fc136a22")
    val network1Json =
        """{
            "status": "ACTIVE",
            "name": "private-network",
            "admin_state_up": true,
            "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
            "shared": true,
            "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
            "router:external": true
        }"""
    val network1Json2 =
        """{
            "status": "ACTIVE",
            "name": "public-network",
            "admin_state_up": false,
            "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
            "shared": true,
            "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
            "router:external": true
        }"""
    val network2Uuid = UUID.fromString("a305c946-fda6-4940-8ab1-fcf0d4d35dfd")
    val network2Json =
        """{
            "status": "ACTIVE",
            "name": "corporate-network",
            "admin_state_up": true,
            "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
            "shared": true,
            "id": "a305c946-fda6-4940-8ab1-fcf0d4d35dfd",
            "router:external": false
        }"""

<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
    override protected def beforeAll() {
=======
    private var curator: CuratorFramework = _
    private var storage: Storage = _
    private var c3po: C3POMinion = _

    // ---------------------
    // TEST SETUP
    // ---------------------

    before {
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
        try {
            val retryPolicy = new ExponentialBackoffRetry(1000, 10)
            curator = CuratorFrameworkFactory.newClient(ZK_HOST, retryPolicy)

            // Populate test data
            createTaskTable()

            // Move this to a beforeAll when more test cases are added
            zk.start()
            curator.start()
            curator.blockUntilConnected()

            storage = new ZoomProvider(curator).get()

            val nodeCtx = new Context(UUID.randomUUID())
            c3po = new C3POMinion(nodeCtx, c3poCfg, dataSrc, storage, curator)
            c3po.startAsync()
            c3po.awaitRunning(2, TimeUnit.SECONDS)
        } catch {
            case e: Throwable =>
                log.error("Failing setting up environment", e)
                cleanup()
        }
   }

    before {
        // Empties the task table and flush the topology before each test run.
        // NOTE: Closing the ZK Testing Server doesn't work because the Curator
        // client loses the connection. If we convert the test so that it runs
        // C3PO alone, we can instead just call C3PO.flushTopology() on C3PO.
        // Calling Storage.flush() doesn't do the job as it doesn't do necessary
        // initialization.
        emptyTaskTableAndSendFlushTask()
    }

    override protected def afterAll() {
        cleanup()
    }

    private def cleanup(): Unit = {
<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
        try {
            log.error("\n\n\n\nCleaning up!\n\n\n\n")
            daemon.stopAsync()
            daemon.awaitTerminated(5000, TimeUnit.MILLISECONDS)
        } finally {
            try {
                zk.stop()
            } catch {
                case NonFatal(t) =>
                    log.warn("ZK Testing Server failed to stop.", t)
            }
        }

        if (dummyConnection != null) dummyConnection.close()
=======
        Try(c3po.stopAsync()).getOrElse(log.error("Failed stopping c3po"))
        Try(curator.close()).getOrElse(log.error("Failed stopping curator"))
        Try(zk.stop()).getOrElse(log.error("Failed stopping zk"))
        Try(if (dummyConnection != null) dummyConnection.close())
            .getOrElse(log.error("Failed stopping zk"))
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
    }

    "C3PO" should "poll DB and update ZK via C3POStorageMgr" in {

        val sleadSleepMs = 2000
        // Initially the Storage is empty.
        storage.exists(classOf[Network], network1Uuid).await() shouldBe false

        // Creates Network 1
<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
        executeSqlStmts(insertMidoNetTaskSql(
                id = 2, Create, NetworkType, network1Json, network1Uuid, "tx1"))
=======
        executeSqlStmts(insertMidoNetTaskSql(2, Create, NetworkType,
                                             network1Json, network1Uuid, "tx1"))
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() shouldBe true

        val network1 = storage.get(classOf[Network], network1Uuid).await()
        network1.getId shouldBe UUIDUtil.toProto(network1Uuid)
        network1.getName shouldBe "private-network"
        network1.getAdminStateUp shouldBe true

        // Creates Network 2 and updates Network 1
        executeSqlStmts(
<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
                insertMidoNetTaskSql(id = 3, Create, NetworkType, network2Json,
                                     network2Uuid, "tx2"),
                insertMidoNetTaskSql(id = 4, Update, NetworkType, network1Json2,
                                     network1Uuid, "tx2"))
=======
            insertMidoNetTaskSql(3, Create, NetworkType, network2Json,
                                 network2Uuid, "tx2"),
            insertMidoNetTaskSql(4, Update, NetworkType, network1Json2,
                                 network1Uuid, "tx2")
        )
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network2Uuid).await() shouldBe true
        val network2 = storage.get(classOf[Network], network2Uuid).await()
        network2.getId shouldBe UUIDUtil.toProto(network2Uuid)
        network2.getName shouldBe "corporate-network"
        val network1a = storage.get(classOf[Network], network1Uuid).await()
        network1a.getId shouldBe UUIDUtil.toProto(network1Uuid)
        network1a.getName shouldBe "public-network"
        network1a.getAdminStateUp shouldBe false

        // Deletes Network 1
<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
        executeSqlStmts(insertMidoNetTaskSql(
                id = 5, Delete, NetworkType, json = "", network1Uuid, "tx3"))
=======
        executeSqlStmts(
            insertMidoNetTaskSql(5, Delete, NetworkType, "", network1Uuid, "tx3")
        )
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() shouldBe false

<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
        // Empties the Task table and flushes the Topology.
        emptyTaskTableAndSendFlushTask()
=======
        // Truncates the Task table and flushes the Storage.
        executeSqlStmts(TRUNCATE_TASK_TABLE,
                        insertMidoNetTaskSql(1, Flush, NoData, "", null, "tx4"))
        Thread.sleep(sleadSleepMs)
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala

        storage.exists(classOf[Network], network2Uuid).await() shouldBe false

        // Can create Network 1 & 2 again.
<<<<<<< HEAD:brain/midonet-brain/src/test/scala/org/midonet/brain/C3PODaemonTest.scala
        executeSqlStmts(
                insertMidoNetTaskSql(id = 2, Create, NetworkType, network1Json,
                                     network1Uuid, "tx4"),
                insertMidoNetTaskSql(id = 3, Create, NetworkType, network2Json,
                                     network2Uuid, "tx4"))
=======
        executeSqlStmts(insertMidoNetTaskSql(2, Create, NetworkType,
                                             network1Json, network1Uuid, "tx5"),
                        insertMidoNetTaskSql(3, Create, NetworkType,
                                             network2Json, network2Uuid, "tx5"))
>>>>>>> Iterate Cluster Minion configs:brain/midonet-brain/src/test/scala/org/midonet/brain/C3POMinionTest.scala
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() shouldBe true
        storage.exists(classOf[Network], network2Uuid).await() shouldBe true
    }
}
