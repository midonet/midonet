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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{FirewallLog, NeutronLoggingResource}
import org.midonet.cluster.models.Topology.LoggingResource.Type
import org.midonet.cluster.models.Topology.{Chain, LoggingResource, RuleLogger}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Operation, Update}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.util.concurrent.toFutureOps

class FirewallLogTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[FirewallLog] with ChainManager {

    import FirewallLogTranslator._

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(fl: FirewallLog): OperationList = {
        val ops = new OperationListBuffer
        ops ++= ensureLoggerResource(fl.getLoggingResource)
        ops ++= createRuleLogger(fl)
        ops ++= ensureMetaData(fl.getFirewallId, fl.getTenantId, fl.getId)
        ops.toList
    }

    def createRuleLogger(fl: FirewallLog): OperationList = {
        List(Create(RuleLogger.newBuilder
                .setId(fl.getId)
                .setChainId(fwdChainId(fl.getFirewallId))
                .setFileName(s"firewall-${fromProto(fl.getId)}.log")
                .setLoggingResourceId(fl.getLoggingResourceId)
                .setEvent(fl.getFwEvent)
                .build()))
    }

    def ensureMetaData(fwId: UUID, tenantId: String, rlId: UUID): OperationList = {
        val chain = storage.get(classOf[Chain], fwdChainId(fwId)).await()
        val chainMetaData = chain.getMetadataList.asScala
        if (!chainMetaData.exists(_.getKey == FIREWALL_ID)) {
            val chainBldr = chain.toBuilder
            chainBldr.addMetadataBuilder()
                .setKey(FIREWALL_ID)
                .setValue(fromProto(fwId).toString)
            chainBldr.addMetadataBuilder()
              .setKey(TENANT_ID)
              .setValue(tenantId)
            chainBldr.addLoggerIds(rlId)
            List(Update(chainBldr.build))
        } else {
            List()
        }
    }

    def ensureLoggerResource(lr: NeutronLoggingResource): OperationList = {
        if (!storage.exists(classOf[LoggingResource], lr.getId).await()) {
            List(Create(LoggingResource.newBuilder
                            .setId(lr.getId)
                            .setEnabled(lr.getEnabled)
                            .setType(Type.FILE)
                            .build()))
        } else {
            List()
        }
    }

    override protected def translateUpdate(fl: FirewallLog): OperationList = {
        val oldLogger = storage.get(classOf[RuleLogger], fl.getId).await()
        val newLogger = oldLogger.toBuilder.setEvent(fl.getFwEvent).build()
        List(Update(newLogger))
    }

    override protected def translateDelete(flId: UUID): OperationList = {
        val ops = new OperationListBuffer
        val rl = storage.get(classOf[RuleLogger], flId).await()
        ops += Delete(classOf[RuleLogger], flId)
        val lr = storage.get(classOf[LoggingResource], rl.getLoggingResourceId).await()
        if (lr.getLoggerIdsCount == 1) {
            ops += Delete(classOf[LoggingResource], lr.getId)
        }
        ops.toList
    }

    override protected def retainHighLevelModel(op: Operation[FirewallLog])
    : List[Operation[FirewallLog]] = {
        op match {
            case Update(_, _) | Create(_) | Delete(_, _) => List()
            case _ => super.retainHighLevelModel(op)
        }
    }
}

object FirewallLogTranslator {
    val FIREWALL_ID = "firewall_id"
    val TENANT_ID = "tenant_id"
}
