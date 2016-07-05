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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{FirewallLog => FirewallLogType, LoggingResource => LoggingResourceType}
import org.midonet.cluster.models.Topology.LoggingResource
import org.midonet.cluster.models.Topology.RuleLogger
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.models.Commons.LogEvent
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class FirewallLoggingTranslationIT extends C3POMinionTestBase with ChainManager {

    "FirewallLogTranslator" should "handle FirewallLog CRUD" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        eventually {
            verifyMedatData(fwId)
            verifyLoggingResource(lrId)
            verifyRuleLogger(fwId, flId)
        }

        insertDeleteTask(11, FirewallLogType, flId)

        eventually {
            storage.exists(classOf[LoggingResource], lrId).await() shouldBe false
            storage.exists(classOf[RuleLogger], flId).await() shouldBe false
        }
    }

    "FirewallLogTranslator" should "handle multiple Firewall Logs on " +
                                   "single logging resource" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val fwId2 = createFirewall(3, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        val flId2 = createFirewallLog(11, fwId2, lrId)

        eventually {
            verifyMedatData(fwId)
            verifyMedatData(fwId2)
            verifyLoggingResource(lrId, 2)
            verifyRuleLogger(fwId, flId)
            verifyRuleLogger(fwId2, flId2)
        }

        insertDeleteTask(12, FirewallLogType, flId)

        eventually {
            verifyMedatData(fwId)
            verifyMedatData(fwId2)
            verifyLoggingResource(lrId, 1)
            verifyRuleLogger(fwId2, flId2)
            storage.exists(classOf[LoggingResource], lrId).await() shouldBe true
            storage.exists(classOf[RuleLogger], flId).await() shouldBe false
            storage.getAll(classOf[LoggingResource]).await().size shouldBe 1
        }

        insertDeleteTask(13, FirewallLogType, flId2)

        eventually {
            verifyMedatData(fwId)
            verifyMedatData(fwId2)
            storage.exists(classOf[RuleLogger], flId).await() shouldBe false
            storage.exists(classOf[RuleLogger], flId2).await() shouldBe false
            storage.exists(classOf[LoggingResource], lrId).await() shouldBe false
        }
    }

    "FirewallLogTranslator" should "handle multiple Firewall Logs on a " +
                                   "multiple logging resource" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val fwId2 = createFirewall(3, adminStateUp = true)
        val lrId = UUID.randomUUID
        val lrId2 = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        val flId2 = createFirewallLog(11, fwId2, lrId2)

        eventually {
            verifyMedatData(fwId)
            verifyMedatData(fwId2)
            verifyLoggingResource(lrId, 1)
            verifyLoggingResource(lrId2, 1)
            verifyRuleLogger(fwId, flId)
            verifyRuleLogger(fwId2, flId2)
        }

        insertDeleteTask(12, FirewallLogType, flId)

        eventually {
            verifyMedatData(fwId)
            verifyMedatData(fwId2)
            verifyLoggingResource(lrId2, 1)
            storage.exists(classOf[LoggingResource], lrId).await() shouldBe false
            verifyRuleLogger(fwId2, flId2)
            storage.exists(classOf[RuleLogger], flId).await() shouldBe false
            storage.getAll(classOf[LoggingResource]).await().size shouldBe 1
        }

        insertDeleteTask(13, FirewallLogType, flId2)

        eventually {
            verifyMedatData(fwId)
            verifyMedatData(fwId2)
            storage.exists(classOf[RuleLogger], flId).await() shouldBe false
            storage.exists(classOf[RuleLogger], flId2).await() shouldBe false
            storage.exists(classOf[LoggingResource], lrId).await() shouldBe false
            storage.exists(classOf[LoggingResource], lrId2).await() shouldBe false
        }
    }

    "FirewallLogTranslator" should "update the rule logger" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        eventually {
            verifyMedatData(fwId)
            verifyLoggingResource(lrId)
            verifyRuleLogger(fwId, flId)
        }

        val json = firewallLogJson(fwId, lrId, fwEvent = "DROP", id = flId)

        insertUpdateTask(11, FirewallLogType, json, flId)

        eventually {
            verifyRuleLogger(fwId, flId, LogEvent.DROP)
        }
    }

    "LoggingResourceTranslator" should "update the enabled flag" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        eventually {
            verifyMedatData(fwId)
            verifyLoggingResource(lrId)
            verifyRuleLogger(fwId, flId)
        }

        val json = loggingResourceJson(enabled = false, id = lrId)

        insertUpdateTask(11, LoggingResourceType, json, lrId)

        eventually {
            verifyLoggingResource(lrId, enabled = false)
        }
    }

    "LoggingResourceTranslator" should "clean up rule loggers" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        eventually {
            verifyMedatData(fwId)
            verifyLoggingResource(lrId)
            verifyRuleLogger(fwId, flId)
        }

        insertDeleteTask(11, LoggingResourceType, lrId)

        eventually {
            storage.exists(classOf[LoggingResource], lrId).await() shouldBe false
            storage.exists(classOf[RuleLogger], flId).await() shouldBe false
        }
    }

    "LoggingResourceTranslator" should "clean up multiple rule loggers" in {
        val fwId = createFirewall(2, adminStateUp = true)
        val fwId2 = createFirewall(3, adminStateUp = true)
        val lrId = UUID.randomUUID
        val flId = createFirewallLog(10, fwId, lrId)
        val flId2 = createFirewallLog(11, fwId2, lrId)

        eventually {
            verifyMedatData(fwId)
            verifyMedatData(fwId2)
            verifyLoggingResource(lrId, 2)
            verifyRuleLogger(fwId, flId)
            verifyRuleLogger(fwId2, flId2)
        }

        insertDeleteTask(12, LoggingResourceType, lrId)

        eventually {
            storage.exists(classOf[LoggingResource], lrId).await() shouldBe false
            storage.exists(classOf[RuleLogger], flId).await() shouldBe false
            storage.exists(classOf[RuleLogger], flId2).await() shouldBe false
        }
    }

    def verifyMedatData(fwId: UUID): Unit = {
        val chain = storage.get(classOf[Chain], fwdChainId(toProto(fwId))).await()
        val md = chain.getMetadataList.asScala
        md map (_.getKey) contains "firewall_id" shouldBe true
        md map (_.getKey) contains "tenant_id" shouldBe true
        md map (_.getValue) contains fwId.toString shouldBe true
    }

    def verifyLoggingResource(lrId: UUID, count: Int = 1,
                              enabled: Boolean = true) = {
        val lr = storage.get(classOf[LoggingResource], lrId).await()
        lr.getLoggerIdsCount shouldBe count
        lr.getEnabled shouldBe enabled
    }

    def verifyRuleLogger(fwId: UUID, rlId: UUID, event: LogEvent = LogEvent.ALL) = {
        val rl = storage.get(classOf[RuleLogger], rlId).await()
        rl.getChainId shouldBe fwdChainId(fwId)
        rl.getEvent shouldBe event
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
                                   fwEvent = fwEvent, id = id)
        insertCreateTask(taskId, FirewallLogType, json, id)
        id
    }
}
