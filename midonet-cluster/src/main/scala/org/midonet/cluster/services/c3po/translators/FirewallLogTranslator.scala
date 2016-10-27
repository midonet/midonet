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

package org.midonet.cluster.services.c3po.translators

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{FirewallLog, NeutronLoggingResource}
import org.midonet.cluster.models.Topology.LoggingResource.Type
import org.midonet.cluster.models.Topology.{Chain, LoggingResource, RuleLogger}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.util.UUIDUtil.fromProto

class FirewallLogTranslator extends Translator[FirewallLog] with ChainManager {

    import FirewallLogTranslator._

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(tx: Transaction,
                                           fl: FirewallLog): OperationList = {
        ensureLoggerResource(tx, fl.getLoggingResource)
        createRuleLogger(tx, fl)
        ensureMetaData(tx, fl.getFirewallId, fl.getTenantId, fl.getId)
        List()
    }

    private def createRuleLogger(tx: Transaction, fl: FirewallLog): Unit = {
        tx.create(RuleLogger.newBuilder
                      .setId(fl.getId)
                      .setChainId(fwdChainId(fl.getFirewallId))
                      .setLoggingResourceId(fl.getLoggingResourceId)
                      .setEvent(fl.getFwEvent)
                      .build())
    }

    private def ensureMetaData(tx: Transaction, fwId: UUID, tenantId: String,
                               rlId: UUID): Unit = {
        val chain = tx.get(classOf[Chain], fwdChainId(fwId))
        val chainMetaData = chain.getMetadataList.asScala

        if (!chainMetaData.exists(_.getKey == FIREWALL_ID)) {
            val chainBuilder = chain.toBuilder
            chainBuilder.addMetadataBuilder()
                .setKey(FIREWALL_ID)
                .setValue(fromProto(fwId).toString)
            chainBuilder.addMetadataBuilder()
                .setKey(TENANT_ID)
                .setValue(tenantId)

            tx.update(chainBuilder.build)
        }
    }

    private def ensureLoggerResource(tx: Transaction,
                                     lr: NeutronLoggingResource): Unit = {
        if (!tx.exists(classOf[LoggingResource], lr.getId)) {
            tx.create(LoggingResource.newBuilder
                          .setId(lr.getId)
                          .setEnabled(lr.getEnabled)
                          .setType(Type.FILE)
                          .build())
        }
    }

    override protected def translateUpdate(tx: Transaction,
                                           fl: FirewallLog): OperationList = {
        val oldLogger = tx.get(classOf[RuleLogger], fl.getId)
        val newLogger = oldLogger.toBuilder.setEvent(fl.getFwEvent).build()
        tx.update(newLogger)
        List()
    }

    override protected def translateDelete(tx: Transaction,
                                           flId: UUID): OperationList = {
        val rl = tx.get(classOf[RuleLogger], flId)
        tx.delete(classOf[RuleLogger], flId, ignoresNeo = true)

        val lr = tx.get(classOf[LoggingResource], rl.getLoggingResourceId)
        if (lr.getLoggerIdsCount == 0) {
            tx.delete(classOf[LoggingResource], lr.getId, ignoresNeo = true)
        }
        List()
    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[FirewallLog])
    : List[Operation[FirewallLog]] = {
        List()
    }
}

object FirewallLogTranslator {
    val FIREWALL_ID = "firewall_id"
    val TENANT_ID = "tenant_id"
}
