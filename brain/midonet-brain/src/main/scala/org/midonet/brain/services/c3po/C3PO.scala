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

package org.midonet.brain.services.c3po

import com.google.inject.Inject
import com.google.protobuf.Message
import org.apache.curator.framework.recipes.leader.LeaderLatch

import org.midonet.brain.services.c3po.NeutronDeserializer.toMessage
import org.midonet.brain.{ScheduledClusterMinion, ScheduledMinionConfig}
import org.midonet.cluster.data.neutron.{SqlNeutronImporter, importer}
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.util.UUIDUtil
import org.midonet.config._

/** The service in charge of loading Neutron models, translate and import them
  * into the MidoNet backend storage. */
class C3PO @Inject()(val config: C3POConfig,
                     val storage: Storage,
                     val leaderLatch: LeaderLatch)
    extends ScheduledClusterMinion(config) {

    val dataMgr = initDataManager()

    val neutronSrvc = new SqlNeutronImporter(config.jdbcDriver,
                                           config.connectionString,
                                           config.user, config.password)

    protected override val runnable = new Runnable {
        override def run(): Unit = try {
            if (!leaderLatch.hasLeadership) {
                log.debug("NeutronPollingThread doing nothing because this " +
                          "node is not the leader.")
                return
            }

            val lastTaskId = dataMgr.lastProcessedTaskId
            log.debug("Got last processed task ID: {}.", lastTaskId)

            val txns = neutronSrvc.getTasksSince(lastTaskId)
            log.debug("Got {} transaction(s) from Neutron: {}", txns.size, txns)

            for (txn <- txns) {
                if (txn.isFlushTxn) {
                    dataMgr.flushTopology()
                    log.info("Deleting flush task from Neutron database.")
                    neutronSrvc.deleteTask(txn.lastTaskId)
                } else {
                    dataMgr.interpretAndExecTxn(translateTxn(txn))
                }
            }
        } catch {
            case ex: Throwable =>
                log.error("Unexpected exception in Neutron polling thread.", ex)
        }
    }

    private def translateTxn(txn: importer.Transaction) =
        neutron.Transaction(txn.id, txn.tasks.map(translateTask))

    private def translateTask(task: importer.Task)
    : neutron.Task[_ <: Message] = {
        val c3poOp: neutron.NeutronOp[_ <: Message] = task match {
            case importer.Create(_, rsrcType, json) =>
                neutron.Create(toMessage(json, rsrcType.clazz))
            case importer.Update(_, rsrcType, json) =>
                neutron.Update(toMessage(json, rsrcType.clazz))
            case importer.Delete(_, rsrcType, objId) =>
                neutron.Delete(rsrcType.clazz, UUIDUtil.toProto(objId))
            case importer.Flush(_) =>
                // TODO: Trigger a rebuild, because this shouldn't happen.
                throw new IllegalArgumentException(
                    "Flush operation not in its own transaction: " + task)
        }
        neutron.Task(task.taskId, c3poOp)
    }

    private def initDataManager(): C3POStorageManager = {
        val dataMgr = new C3POStorageManager(storage)
        dataMgr.registerTranslator(classOf[NeutronNetwork],
                                   new NetworkTranslator)
        dataMgr.init()
        dataMgr
    }
}

@ConfigGroup("neutron-importer")
trait C3POConfig extends ScheduledMinionConfig[C3PO] {
    @ConfigBool(key = "enabled")
    override def isEnabled: Boolean

    @ConfigString(key = "with")
    override def minionClass: String

    @ConfigInt(defaultValue = 1)
    override def numThreads: Int

    @ConfigLong(key = "delay_ms", defaultValue = 0)
    override def delayMs: Long

    @ConfigLong(key = "period_ms", defaultValue = 1000)
    override def periodMs: Long

    @ConfigString(key = "connection_str")
    def connectionString: String

    @ConfigString(key = "jdbc_driver_class")
    def jdbcDriver: String

    @ConfigString(key = "user")
    def user: String

    @ConfigString(key = "password", defaultValue = "")
    def password: String
}
