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

import java.util.UUID

import com.fasterxml.jackson.databind.JsonNode

import scala.collection.JavaConverters._

import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{FirewallLog => FirewallLogType, LoggingResource => LoggingResourceType}
import org.midonet.cluster.models.Topology.LoggingResource
import org.midonet.cluster.models.Topology.Chain
import org.midonet.util.concurrent.toFutureOps

class FirewallLoggingTranslationIT extends C3POMinionTestBase with ChainManager {

    "FirewallLogTranslator" should "handle FirewallLog CRUD" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        eventually {
            val chain = storage.get(classOf[Chain], fwdChainId(toProto(fwId))).await()
            val md =
            chain.getMetadataList.asScala map (_.getKey) exists (_ == "firewall")
            val lr = storage.get(classOf[LoggingResource], lrId).await()
            lr.getFileName shouldBe s"firewall-$lrId.log"
            lr.getLoggerIdsCount shouldBe 1
        }
    }

    "FirewallLogTranslator" should "handle multiple Firewall Logs on a " +
                                   "single logging resource" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)

    }

    protected def loggingResourceJson(description: Option[String] = None,
                                      enabled: Boolean = true,
                                      id: UUID = UUID.randomUUID(),
                                      name: Option[String] = None,
                                      tenantId: String = "tenant"): JsonNode = {
        val n = nodeFactory.objectNode
        n.put("description", description.getOrElse("Logging resource " + id))
        n.put("enabled", enabled)
        n.put("id", id.toString)
        n.put("name", name.getOrElse("loggging-resource-" + id))
        n.put("tenant_id", tenantId)
        n
    }

    protected def firewallLogJson(firewallId: UUID,
                                  loggingResourceId: UUID,
                                  description: Option[String] = None,
                                  fwEvent: String = "ALL",
                                  id: UUID = UUID.randomUUID(),
                                  tenantId: String = "tenant"): JsonNode = {
        val n = nodeFactory.objectNode
        n.put("description",
              description.getOrElse(s"Logger for firewall $firewallId and " +
                                    s"logging resource $loggingResourceId"))
        n.put("firewall_id", firewallId.toString)
        n.put("fw_event", fwEvent)
        n.put("id", id.toString)
        n.put("logging_resource_id", loggingResourceId.toString)
        n.put("tenant_id", tenantId)
        val lrNode = nodeFactory.objectNode()
        lrNode.put("id", loggingResourceId.toString)
        lrNode.put("enabled", true)
        n.set("logging_resource", lrNode)
        n
    }

    protected def createLoggingResource(taskId: Int,
                                        id: UUID = UUID.randomUUID(),
                                        enabled: Boolean = true): UUID = {
        val json = loggingResourceJson(id = id, enabled = enabled)
        insertCreateTask(taskId, LoggingResourceType, json, id)
        id
    }

    protected def createFirewallLog(taskId: Int,
                                    firewallId: UUID,
                                    loggingResourceId: UUID,
                                    id: UUID = UUID.randomUUID(),
                                    fwEvent: String = "ALL"): UUID = {
        val json = firewallLogJson(firewallId, loggingResourceId,
                                   fwEvent = fwEvent)
        insertCreateTask(taskId, FirewallLogType, json, id)
        id
    }
}
