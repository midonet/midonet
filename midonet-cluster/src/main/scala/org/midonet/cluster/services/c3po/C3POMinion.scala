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

package org.midonet.cluster.services.c3po

import java.sql.Driver

import javax.sql.DataSource

import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.protobuf.Message

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.neutron.{DataStateUpdater, SqlNeutronImporter, importer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.C3POStorageManager._
import org.midonet.cluster.services.c3po.NeutronDeserializer.toMessage
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.{C3POConfig, C3poLog, ClusterConfig}
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.ScheduledMinion.checkConfigParamDefined
import org.midonet.minion.{Context, MinionService, ScheduledMinion}

/** The service that translates and imports neutron models into the MidoNet
  * backend storage.
  *
  * @param nodeContext metadata of the Cluster Node where we're running
  * @param config the configuration of the C3PO service
  * @param dataSrc API for access to the the Neutron DB
  * @param backend The MidoNet backend service
  * @param curator API for access to ZK for internal uses of the C3PO service
  * @param backendCfg the Backend configuration
  */
@MinionService(name = "neutron-importer", runsOn = TargetNode.CLUSTER)
class C3POMinion @Inject()(nodeContext: Context,
                           config: ClusterConfig,
                           dataSrc: DataSource,
                           backend: MidonetBackend,
                           curator: CuratorFramework,
                           backendCfg: MidonetBackendConfig)
    extends ScheduledMinion(nodeContext, config.c3po) {

    protected override val log = LoggerFactory.getLogger(C3poLog)

    private val neutronImporter = new SqlNeutronImporter(dataSrc)
    private val dataStateUpdater = new DataStateUpdater(dataSrc)

    private val dataMgr = C3POMinion.initDataManager(config, backend)

    private val LEADER_LATCH_PATH = backendCfg.rootKey + "/leader-latch"
    private val leaderLatch = new LeaderLatch(curator, LEADER_LATCH_PATH,
                                              nodeContext.nodeId.toString)

    override def isEnabled = config.c3po.isEnabled

    override def doStart(): Unit = {
        leaderLatch.start()
        super.doStart()
    }
    override def doStop(): Unit = {
        if (leaderLatch.hasLeadership) {
            log.info("Leader shutting down, releasing leadership")
        } else {
            log.info("Non leader shutting down, removing myself from pool")
        }
        leaderLatch.close()
        super.doStop()
    }

    // Delegates to a static method to enable testing without creating a
    // C3POMinion instance.
    override protected def validateConfig(): Unit =
        C3POMinion.validateConfig(config.c3po)

    protected override val runnable = new Runnable {
        override def run(): Unit = try {
            if (!leaderLatch.hasLeadership) {
                log.debug("Still idle as this node is not the leader.")
                return
            }

            log.debug("I'm leader; syncing from Neutron DB..")

            val lastTaskId = dataMgr.lastProcessedTaskId
            log.debug(".. last processed task ID: {}.", lastTaskId)

            val txns = neutronImporter.getTasksSince(lastTaskId)
            log.debug(".. {} transaction(s) to import: {}", txns.size, txns)

            for (txn <- txns) {
                if (txn.isFlushTxn) {
                    log.info(".. flushing storage")
                    dataMgr.flushTopology()
                    neutronImporter.deleteTask(txn.lastTaskId)
                } else {
                    dataMgr.interpretAndExecTxn(translateTxn(txn))
                }
            }

            val newLastTaskId = dataMgr.lastProcessedTaskId
            log.debug(".. updating last processed task ID: {}.", newLastTaskId)
            if (C3POState.NO_TASKS_PROCESSED != newLastTaskId)
                dataStateUpdater.updateLastProcessedId(newLastTaskId)
        } catch {
            case NonFatal(t) =>
                log.error("Unexpected exception in NeutronDB polling thread", t)
        }
    }

    private def translateTxn(txn: importer.Transaction) =
        Transaction(txn.id, txn.tasks.map(translateTask))

    private def translateTask(task: importer.Task)
    : Task[_ <: Message] = {
        val c3poOp: Operation[_ <: Message] = task match {
            case importer.Create(_, rsrcType, json) =>
                Create(toMessage(json, rsrcType.clazz))
            case importer.Update(_, rsrcType, json) =>
                Update(toMessage(json, rsrcType.clazz))
            case importer.Delete(_, rsrcType, objId) =>
                Delete(rsrcType.clazz, UUIDUtil.toProto(objId))
            case importer.Flush(_) =>
                // TODO: Trigger a rebuild, because this shouldn't happen.
                throw new IllegalArgumentException(
                    "Flush operation not in its own transaction: " + task)
        }
        Task(task.taskId, c3poOp)
    }
}

object C3POMinion {

    val CnxnStrCfgKey = "cluster.neutron_importer.connection_string"
    val JdbcDriverCfgKey = "cluster.neutron_importer.jdbc_driver_class"

    val JdbcDriverClassNotFoundErrMsg = "Could not load JDBC driver class: %s."
    val NotDriverSubclassErrMsg =
        s"The class specified by $JdbcDriverCfgKey, %s, is not a subclass of " +
        "java.sql.Driver."
    val InvalidCnxnStrErrMsg =
        s"The connection string specified in $CnxnStrCfgKey is not a valid " +
        "connection string for the specified JDBC driver."

    def validateConfig(cfg: C3POConfig): Unit = {
        val driverClassStr = cfg.jdbcDriver.trim
        val cnxnStr = cfg.connectionString.trim
        checkConfigParamDefined(driverClassStr, JdbcDriverCfgKey)
        checkConfigParamDefined(cnxnStr, CnxnStrCfgKey)

        val driverClass = try Class.forName(driverClassStr) catch {
            case NonFatal(t) => throw new ClassNotFoundException(
                JdbcDriverClassNotFoundErrMsg.format(driverClassStr), t)
        }

        if (!classOf[Driver].isAssignableFrom(driverClass))
            throw new IllegalArgumentException(
                NotDriverSubclassErrMsg.format(driverClass.getName))

        val driver = driverClass.newInstance().asInstanceOf[Driver]
        if (!driver.acceptsURL(cnxnStr))
            throw new IllegalArgumentException(
                InvalidCnxnStrErrMsg.format(cnxnStr))
    }

    def initDataManager(config: ClusterConfig,
                        backend: MidonetBackend): C3POStorageManager = {
        val manager = new C3POStorageManager(config, backend)
        manager.init()
        manager
    }
}

