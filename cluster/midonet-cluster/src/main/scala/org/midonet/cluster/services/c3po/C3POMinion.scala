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

package org.midonet.cluster.services.c3po

import javax.sql.DataSource

import com.google.inject.Inject
import com.google.protobuf.Message

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.neutron.{DataStateUpdater, SqlNeutronImporter, importer}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.NeutronDeserializer.toMessage
import org.midonet.cluster.services.c3po.translators._
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.{ClusterConfig, ClusterNode, ScheduledClusterMinion}
import org.midonet.midolman.state.PathBuilder

/** The service that translates and imports neutron models into the MidoNet
  * backend storage
  *
  * @param nodeContext metadata of the Cluster Node where we're running
  * @param config the configuration of the C3PO service
  * @param dataSrc API for access to the the Neutron DB
  * @param backend The MidoNet backend service
  * @param curator API for access to ZK for internal uses of the C3PO serviceD/H
  */
class C3POMinion @Inject()(nodeContext: ClusterNode.Context,
                           config: ClusterConfig,
                           dataSrc: DataSource,
                           backend: MidonetBackend,
                           curator: CuratorFramework,
                           backendCfg: MidonetBackendConfig)
    extends ScheduledClusterMinion(nodeContext, config.c3po) {

    private val log = LoggerFactory.getLogger(classOf[C3POMinion])

    private val storage = backend.store
    private val dataMgr = initDataManager()

    private val neutronImporter = new SqlNeutronImporter(dataSrc)
    private val dataStateUpdater = new DataStateUpdater(dataSrc)

    private val LEADER_LATCH_PATH = "/leader-latch"
    private val leaderLatch = new LeaderLatch(curator, LEADER_LATCH_PATH,
                                              nodeContext.nodeId.toString)
    leaderLatch.start()

    override def doStop(): Unit = {
        if (leaderLatch.hasLeadership) {
            log.info("Leader shutting down, releasing leadership")
        } else {
            log.info("Non leader shutting down, removing myself from pool")
        }
        leaderLatch.close()
        super.doStop()
    }

    protected override val runnable = new Runnable {
        override def run(): Unit = try {
            if (!leaderLatch.hasLeadership) {
                log.debug("NeutronPollingThread doing nothing because this " +
                          "node is not the leader.")
                return
            }

            log.debug("Cluster leader; syncing from Neutron DB..")

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
        val pathBldr = new PathBuilder(backendCfg.rootKey)
        List(classOf[AgentMembership] -> new AgentMembershipTranslator(storage),
             classOf[FloatingIp] -> new FloatingIpTranslator(storage),
             classOf[NeutronConfig] -> new ConfigTranslator(storage),
             classOf[NeutronHealthMonitor] ->
                 new HealthMonitorTranslator(storage),
             classOf[NeutronLoadBalancerPool] ->
                 new LoadBalancerPoolTranslator(storage),
             classOf[NeutronLoadBalancerPoolMember] ->
                 new LoadBalancerPoolMemberTranslator(storage),
             classOf[NeutronNetwork] ->
                 new NetworkTranslator(storage, pathBldr),
             classOf[NeutronRouter] -> new RouterTranslator(storage),
             classOf[NeutronRouterInterface] ->
                new RouterInterfaceTranslator(storage),
             classOf[NeutronSubnet] -> new SubnetTranslator(storage),
             classOf[NeutronPort] -> new PortTranslator(storage, pathBldr),
             classOf[NeutronVIP] -> new VipTranslator(storage),
             classOf[PortBinding] -> new PortBindingTranslator(storage),
             classOf[SecurityGroup] -> new SecurityGroupTranslator(storage)
        ).asInstanceOf[List[(Class[Message], NeutronTranslator[Message])]]
         .foreach(pair => dataMgr.registerTranslator(pair._1, pair._2))

        dataMgr.init()
        dataMgr
    }
}

