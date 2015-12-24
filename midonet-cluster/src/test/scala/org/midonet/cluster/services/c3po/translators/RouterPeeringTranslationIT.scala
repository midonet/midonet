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

package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import com.fasterxml.jackson.databind.JsonNode

import org.junit.runner.RunWith
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{L2GatewayConnection => L2ConnType, GatewayDevice => GatewayDeviceType}
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class RouterPeeringTranslationIT extends C3POMinionTestBase with ChainManager {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true
    import L2GatewayConnectionTranslator._


    protected def gatewayDeviceJson(resourceId: UUID,
                                    id: UUID = UUID.randomUUID(),
                                    gwType: String = "router_vtep",
                                    tunnelIps: Seq[String] = Seq(),
                                    mgmtIp: String = "199.0.0.1",
                                    mgmtPort: Int = 37000,
                                    mgmtProtocol: String = "ovsdb")
    : JsonNode = {
        val d = nodeFactory.objectNode
        d.put("resource_id", resourceId.toString)
        d.put("id", id.toString)
        d.put("type", gwType)
        val tunnelIpsArray = d.putArray("tunnel_ips")
        for (ip <- tunnelIps) tunnelIpsArray.add(ip)
        d.put("management_ip", mgmtIp)
        d.put("management_port", mgmtPort)
        d.put("management_protocol", mgmtProtocol)
        d
    }

    protected case class L2GatewayDevice(deviceId: UUID, segmentationId: Int)

    protected def l2GatewayJson(devices: Seq[L2GatewayDevice],
                                id: UUID = UUID.randomUUID(),
                                name: Option[String] = None,
                                tenantId: String = "tenant"): JsonNode = {
        val g = nodeFactory.objectNode
        g.put("id", id.toString)
        g.put("name", name.getOrElse("l2-gateway-" + id))
        g.put("tenant_id", tenantId)
        val devArray = g.putArray("devices")
        for (dev <- devices) {
            val devNode = devArray.addObject()
            devNode.put("device_id", dev.deviceId.toString)
            devNode.put("segmentation_id", dev.segmentationId)
        }
        g
    }

    protected def l2GatewayConnectionJson(networkId: UUID,
                                          devices: Seq[L2GatewayDevice],
                                          id: UUID = UUID.randomUUID(),
                                          tenantId: String = "tenant")
    : JsonNode = {
        val c = nodeFactory.objectNode
        c.put("network_id", networkId.toString)
        c.put("id", id.toString)
        c.put("tenant_id", tenantId)
        c.set("l2_gateway", l2GatewayJson(devices, tenantId = tenantId))
    }

    protected def remoteMacEntryJson(deviceId: UUID,
                                     vtepAddress: String,
                                     macAddress: String,
                                     segmentationId: Int,
                                     id: UUID = UUID.randomUUID()): JsonNode = {
        val e = nodeFactory.objectNode
        e.put("device_id", deviceId.toString)
        e.put("vtep_address", vtepAddress)
        e.put("mac_address", macAddress)
        e.put("segmentation_id", segmentationId)
        e.put("id", id.toString)
    }

    "L2 Gateway Connection with no devices" should "be created" in {

        val netId = UUID.randomUUID()
        createTenantNetwork(10, netId)

        val rId = UUID.randomUUID()
        createRouter(20, rId)

        val gwId = UUID.randomUUID()
        val gwJson = gatewayDeviceJson(resourceId = rId, id = gwId,
                                       tunnelIps = Seq("30.0.0.1"))
        insertCreateTask(30, GatewayDeviceType, gwJson, gwId)

        val l2gwConnId = UUID.randomUUID()
        val l2gwConnJson = l2GatewayConnectionJson(netId, Seq(L2GatewayDevice(gwId, 100)), id = l2gwConnId)
        insertCreateTask(50, L2ConnType, l2gwConnJson, l2gwConnId)

        eventually {
            val pId = vtepNetworkPortId(toProto(netId))
            storage.exists(classOf[Port], pId).await() shouldBe true
        }
    }
}