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
import java.nio.file.{FileSystems, Files}
import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.concurrent.TimeUnit

import javax.sql.DataSource

import scala.util.Random

import com.google.inject.{AbstractModule, Guice}

import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.brain.ClusterNode.MinionDef
import org.midonet.brain.services.StorageModule
import org.midonet.brain.services.c3po.C3POConfig
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.data.neutron.{NeutronResourceType => ResType, TaskType}
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.util.UUIDUtil
import org.midonet.config.ConfigProvider
import org.midonet.util.concurrent.toFutureOps

/**
 * Tests the Neutron data importer daemon.
 */
@RunWith(classOf[JUnitRunner])
class C3PODaemonTest extends FlatSpec with BeforeAndAfter with Matchers {
    private val log = LoggerFactory.getLogger(this.getClass)

    private val zkPort = 50000 + Random.nextInt(15000)
    private val zkHost = s"127.0.0.1:$zkPort"
    private val dbFile = "taskdb"
    private val dbConnectStr = s"jdbc:sqlite:$dbFile"
    private val dbDriver = "org.sqlite.JDBC"

    private val cfg = fillConfig(new HierarchicalConfiguration)
    private val cfgProvider = ConfigProvider.providerForIniConfig(cfg)

    private val c3poCfg =
        cfgProvider.getConfig(classOf[C3POConfig])
    private val minionDefs: List[MinionDef[ClusterMinion]] =
        List (new MinionDef("neutron-importer", c3poCfg))
    private val daemon = new Daemon(minionDefs)
    private val zk: TestingServer = new TestingServer(zkPort)

    // Adapt the DriverManager interface to DataSource interface.
    // SQLite doesn't seem to provide JDBC 2.0 API.
    private val dataSrc = new DataSource() {
        override def getConnection() = DriverManager.getConnection(dbConnectStr)
        override def getConnection(username: String, password: String) = null
        override def getLoginTimeout = -1
        override def getLogWriter = null
        override def setLoginTimeout(seconds: Int) {}
        override def setLogWriter(out: PrintWriter) {}
        override def getParentLogger = null
        override def isWrapperFor(clazz: Class[_]) = false
        override def unwrap[T](x: Class[T]): T = null.asInstanceOf[T]
    }

    private val clusterNodeTestModule = new AbstractModule {
        override def configure(): Unit = {
            bind(classOf[ConfigProvider]).toInstance(cfgProvider)
            bind(classOf[C3POConfig]).toInstance(c3poCfg)
            minionDefs foreach { m =>
                install(MinionConfig.module(m.cfg))
            }
            bind(classOf[DataSource]).toInstance(dataSrc)
            bind(classOf[Daemon]).toInstance(daemon)
        }
    }

    val injector = Guice.createInjector(clusterNodeTestModule,
                                        new StorageModule(cfgProvider))
    private val storage = injector.getInstance(classOf[Storage])

    protected def fillConfig(config: HierarchicalConfiguration)
            : HierarchicalConfiguration = {
        config.setProperty("curator.zookeeper_hosts", zkHost)
        config.setProperty("curator.base_retry_ms", 100)
        config.setProperty("curator.max_retries", 20)
        config.setProperty("curator.topology_path", "midonet/v2")
        config.setProperty("neutron-importer.enabled", true)
        config.setProperty("neutron-importer.with",
                           "org.midonet.brain.services.c3po.C3PO")
        config.setProperty("neutron-importer.period_ms", 100)
        config.setProperty("neutron-importer.delay_ms", 0)
        config.setProperty("neutron-importer.connection_str", dbConnectStr)
        config.setProperty("neutron-importer.jdbc_driver_class", dbDriver)
        config.setProperty("user", "")
        config.setProperty("password", "")
        config
    }

    private def executeSqlStmts(sqls: String*) {
        val c = dataSrc.getConnection()
        try {
            val stmt = c.createStatement()
            sqls.foreach { sql => stmt.executeUpdate(sql) }
            stmt.close()
        } finally {
            c.close()
        }
    }

    def createTaskTable() = {
        // Just in case an old DB file / table exits.
        executeSqlStmts(dropMidoNetTasksTable)
        executeSqlStmts(createMidoNetTasksTable)
        log.info("Created the midonet_tasks table.")
    }

    val dropMidoNetTasksTable = "DROP TABLE IF EXISTS midonet_tasks"

    val createMidoNetTasksTable =
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

    private def insertMidoNetTaskSql(
            id: Int, taskType: TaskType, dataType: ResType[_],
            json: String, resourceId: UUID, txnId: String) : String = {
        val rsrcIdStr = if (resourceId != null) s"'$resourceId'"
                        else "NULL"
        "INSERT INTO midonet_tasks values(" +
        s"$id, ${taskType.id}, ${dataType.id},'$json', $rsrcIdStr, '$txnId', " +
        "datetime('now'))"
    }

    private def truncateMidoNetTaskTable = "DELETE FROM midonet_tasks"

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

    before {
        ClusterNode.injector = injector
        createTaskTable()

        log info "Test ZK server starts.."
        zk.start()

        log info "MidoNet Cluster daemon starts.."
        daemon.startAsync().awaitRunning()
        log info "MidoNet Cluster is up"
    }

    after {
        try {
            daemon.stopAsync()
            daemon.awaitTerminated(5000, TimeUnit.MILLISECONDS)
        } finally {
            zk.stop()
        }

        try {
            Files.deleteIfExists(FileSystems.getDefault.getPath(dbFile))
        } catch {
            case th: Throwable =>
                log.warn("Failed to delete the test SQLite DB file: {}",
                         th.getMessage)
        }
    }

    "C3PO" should "poll DB and update ZK via C3POStorageMgr" in {
        val sleadSleepMs = 2000
        // Initially the Storage is empty.
        storage.exists(classOf[Network], network1Uuid).await() should be(false)

        // Creates Network 1
        executeSqlStmts(insertMidoNetTaskSql(2, Create, ResType.Network,
                                             network1Json, network1Uuid, "aaa"))
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() should be (true)
        val network1 = storage.get(classOf[Network], network1Uuid).await()
        network1.getId should be (UUIDUtil.toProto(network1Uuid))
        network1.getName should be ("private-network")
        network1.getAdminStateUp should be (true)

        // Creates Network 2 and updates Network 1
        executeSqlStmts(
            insertMidoNetTaskSql(3, Create, ResType.Network,
                                 network2Json, network2Uuid, "bbb"),
            insertMidoNetTaskSql(4, Update, ResType.Network,
                                 network1Json2, network1Uuid, "bbb"))
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network2Uuid).await() should be (true)
        val network2 = storage.get(classOf[Network], network2Uuid).await()
        network2.getId should be (UUIDUtil.toProto(network2Uuid))
        network2.getName should be ("corporate-network")
        val network1a = storage.get(classOf[Network], network1Uuid).await()
        network1a.getId should be (UUIDUtil.toProto(network1Uuid))
        network1a.getName should be ("public-network")
        network1a.getAdminStateUp should be (false)

        // Deletes Network 1
        executeSqlStmts(insertMidoNetTaskSql(5, Delete, ResType.Network,
                                             "", network1Uuid, "ccc"))
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() should be (false)

        // Truncates the Task table and flushes the Storage.
        executeSqlStmts(truncateMidoNetTaskTable,
                        insertMidoNetTaskSql(1, Flush, ResType.NoData,
                                             "", null, "ddd"))
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network2Uuid).await() should be (false)

        // Can create Network 1 & 2 again.
        executeSqlStmts(insertMidoNetTaskSql(2, Create, ResType.Network,
                                             network1Json, network1Uuid, "aaa"),
                        insertMidoNetTaskSql(3, Create, ResType.Network,
                                             network2Json, network2Uuid, "bbb"))
        Thread.sleep(sleadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() should be (true)
        storage.exists(classOf[Network], network2Uuid).await() should be (true)
    }
}
