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

package org.midonet.cluster.data.neutron

import java.util.concurrent.{TimeUnit, Executors, ScheduledExecutorService}

import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.midonet.cluster.data.storage._

import NeutronDeserializer.toMessage
import org.slf4j.LoggerFactory

@Inject
class NeutronPollingThread(curator: CuratorFramework,
                           store: Storage,
                           neutron: NeutronService,
                           hasLeadership: => Boolean) extends Thread {

    private val log = LoggerFactory.getLogger(classOf[NeutronPollingThread])

    val lastProcessedTaskIdPath = "/neutron/lastTaskId"

    override def run(): Unit = {
        if (!hasLeadership) {
            log.debug("NeutronPollingThread doing nothing because this node " +
                      "is not the leader.")
            return
        }

        val lastTaskId = lastProcessedTaskId
        val txns = neutron.getTasksSince(lastTaskId)

        for (txn <- txns) {
            val txnOps = translateTxn(txn)
            // TODO: Pass transactions to Tomohiko's code.

            if (isFlushTxn(txnOps)) {
                log.info("Deleting flush task from Neutron database.")
                neutron.deleteTask(txn.lastTaskId)
            }
        }
    }

    private def isFlushTxn(ops: List[PersistenceOp]) =
        ops.size == 1 && ops(0).isInstanceOf[FlushOp]

    private def lastProcessedTaskId: Int = {
        log.debug("Querying ZK for last processed task ID.")
        val bytes = curator.getData.forPath(lastProcessedTaskIdPath)
        new String(bytes).toInt
    }

    private def translateTxn(txn: Transaction): List[PersistenceOp] = {
        txn.tasks.map(translateTask)
    }

    private def translateTask(task: Task): PersistenceOp = task match {
        case Create(_, rsrcType, json) =>
            CreateOp(toMessage(json, rsrcType.clazz))
        case Update(_, rsrcType, json) =>
            UpdateOp(toMessage(json, rsrcType.clazz))
        case Delete(_, rsrcType, objId) =>
            DeleteOp(rsrcType.clazz, objId)
        case Flush(id) => FlushOp()
    }
}
