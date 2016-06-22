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
import org.midonet.cluster.models.Commons.{MetadataEntry, UUID}
import org.midonet.cluster.models.Neutron.{FirewallLog, NeutronFirewall, NeutronLoggingResource}
import org.midonet.cluster.models.Topology.{Chain, LoggingResource, RuleLogger}
import org.midonet.cluster.models.Topology.LoggingResource.Type
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Operation, Update}
import org.midonet.util.concurrent.toFutureOps

class FirewallLogTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[FirewallLog] with ChainManager {

    import FirewallLogTranslator._

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(fl: FirewallLog): OperationList = {
        val ops = new OperationListBuffer
        ops ++= ensureLoggerResource(fl.getLoggingResource)
        ops ++= createRuleLogger(fl)
        ops ++= ensureMetaData(fl.getFirewallId, fl.getTenantId)
        ops.toList
    }

    def createRuleLogger(fl: FirewallLog): OperationList = {
        List(Create(RuleLogger.newBuilder
                .setId(fl.getId)
                .setChainId(fwdChainId(fl.getFirewallId))
                .setLoggingResourceId(fl.getLoggingResourceId)
                .setEvent(fl.getFwEvent)
                .build()))
    }

    def ensureMetaData(fwId: UUID, tenantId: String): OperationList = {
        val chain = storage.get(classOf[Chain], fwdChainId(fwId)).await()
        val chainBldr = chain.toBuilder
        val chainMetaData = chain.getMetadataList.asScala
        var changed = false
        if (!chainMetaData.exists(_.getKey == FIREWALL_ID)) {
            val md = MetadataEntry.newBuilder
                .setKey(FIREWALL_ID)
                .setValue(fwId.toString)
                .build()
            chainBldr.addMetadata(md)
            changed = true
        }
        if (!chainMetaData.exists(_.getKey == TENANT_ID)) {
            val md = MetadataEntry.newBuilder
              .setKey(TENANT_ID)
              .setValue(tenantId)
              .build()
            chainBldr.addMetadata(md)
            changed = true
        }
        if (changed) {
            List(Update(chainBldr.build))
        } else {
            List()
        }
    }

    def ensureLoggerResource(lr: NeutronLoggingResource): OperationList = {
        if (!storage.exists(classOf[LoggingResource], lr.getId).await()) {
            List(Create(LoggingResource.newBuilder
                            .setId(lr.getId)
                            .setFileName(s"firewall-${fromProto(lr.getId)}.log")
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

    override protected def translateDelete(fl: FirewallLog): OperationList = {
        val lr = storage.get(classOf[LoggingResource], fl.getLoggingResourceId).await()
        if (lr.getLoggerIdsCount == 1) {
            List(Delete(classOf[RuleLogger], fl.getId))
        } else {
            List()
        }
    }

    override protected def retainHighLevelModel(op: Operation[FirewallLog])
    : List[Operation[FirewallLog]] = {
        op match {
            case Update(nm, _) => List()  // See translateUpdate
            case Create(nm) => List()  // See translateUpdate
            case _ => super.retainHighLevelModel(op)
        }
    }
}

object FirewallLogTranslator {
    val FIREWALL_ID = "firewall_id"
    val TENANT_ID = "tenant_id"
}
