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

import org.apache.curator.framework.recipes.leader.LeaderLatch

import org.midonet.brain.services.{ScheduledClusterMinion, ScheduledMinionConfig}
import org.midonet.brain.services.c3po.NeutronDeserializer.toMessage
import org.midonet.cluster.data.neutron._
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.services.c3po._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.config._

class NeutronImporter @Inject()(config: NeutronImporterConfig,
                                storage: Storage,
                                leaderLatch: LeaderLatch)
    extends ScheduledClusterMinion(config) {

    val dataMgr = new C3POStorageManager(storage)
    dataMgr.init()

    val neutron = new RemoteNeutronService(config.jdbcDriver,
                                           config.connectionString,
                                           config.user, config.password)

    protected override val runnable = new Runnable {
        override def run(): Unit = try {
            if (!leaderLatch.hasLeadership) {
                log.debug("NeutronPollingThread doing nothing because this " +
                          "node is not the leader.")
                return
            }

            val lastTaskId = dataMgr.lastProcessedC3POTaskId
            log.debug("Got last processed task ID: {}.", lastTaskId)

            val txns = neutron.getTasksSince(lastTaskId)
            log.debug("Got {} transaction(s) from Neutron: {}", txns.size, txns)

            for (txn <- txns) {
                if (txn.isFlushTxn) {
                    dataMgr.flushTopology()
                    log.info("Deleting flush task from Neutron database.")
                    neutron.deleteTask(txn.lastTaskId)
                } else {
                    dataMgr.interpretAndExecTxn(translateTxn(txn))
                }
            }
        } catch {
            case ex: Throwable =>
                log.error("Unexpected exception in Neutron polling thread.", ex)
        }
    }

    private def translateTxn(txn: Transaction) =
        C3POTransaction(txn.id, txn.tasks.map(translateTask))

    private def translateTask(task: Task): C3POTask[_] = {
        val c3poOp: C3POOp[_ <: Object] = task match {
            case Create(_, rsrcType, json) =>
                C3POCreate(toMessage(json, rsrcType.clazz))
            case Update(_, rsrcType, json) =>
                C3POUpdate(toMessage(json, rsrcType.clazz))
            case Delete(_, rsrcType, objId) =>
                C3PODelete(rsrcType.clazz, UUIDUtil.toProto(objId))
            case Flush(_) =>
                // TODO: Trigger a rebuild, because this shouldn't happen.
                throw new IllegalArgumentException(
                    "Flush operation not in its own transaction: " + task)
        }
        C3POTask(task.taskId, c3poOp)
    }
}

@ConfigGroup("neutron-importer")
trait NeutronImporterConfig extends ScheduledMinionConfig[NeutronImporter] {
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
