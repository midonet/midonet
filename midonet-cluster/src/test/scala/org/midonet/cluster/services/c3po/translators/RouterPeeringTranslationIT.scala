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
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{GatewayDevice => GatewayDeviceType, L2GatewayConnection => L2ConnType, RemoteMacEntry => RemoteMacEntryType}
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection, RemoteMacEntry}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Addr, MAC}
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
                                          segmentationId: Int,
                                          devices: Seq[L2GatewayDevice],
                                          id: UUID = UUID.randomUUID(),
                                          tenantId: String = "tenant")
    : JsonNode = {
        val c = nodeFactory.objectNode
        c.put("network_id", networkId.toString)
        c.put("segmentation_id", segmentationId)
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

    private def createGatewayDevice(taskId: Int,
                                    id: UUID = UUID.randomUUID(),
                                    routerId: UUID = routerId,
                                    tunnelIps: Seq[String] = Seq("30.0.0.1"))
    : UUID = {
        val json = gatewayDeviceJson(resourceId = routerId, id = id,
                                     tunnelIps = tunnelIps)
        insertCreateTask(taskId, GatewayDeviceType, json, id)
        id
    }

    private case class RemoteMac(id: UUID, ip: IPv4Addr, mac: MAC, vni: Int)
    private def createRemoteMacEntry(taskId: Int, gwDevId: UUID, vni: Int,
                                     id: UUID = UUID.randomUUID(),
                                     vtepIp: IPv4Addr = IPv4Addr.random,
                                     mac: MAC = MAC.random())
    : RemoteMac = {
        val json = remoteMacEntryJson(gwDevId, vtepIp.toString,
                                      mac.toString, vni, id)
        insertCreateTask(taskId, RemoteMacEntryType, json, id)
        RemoteMac(id, vtepIp, mac, vni)
    }

    private def createL2GatewayConnection(taskId: Int, gwDevId: UUID,
                                          id: UUID = UUID.randomUUID(),
                                          networkId: UUID = networkId,
                                          vni: Int = 100): UUID = {
        val json = l2GatewayConnectionJson(
            networkId, vni, Seq(L2GatewayDevice(gwDevId, vni)), id)
        insertCreateTask(taskId, L2ConnType, json, id)
        id
    }

    private val routerId = UUID.randomUUID()
    private val networkId = UUID.randomUUID()

    "L2 Gateway Connection with no devices" should "be created" ignore {

        createTenantNetwork(10, networkId)
        createRouter(20, routerId)
        val gwDevId = createGatewayDevice(30)
        createL2GatewayConnection(40, gwDevId)

        eventually {
            val pId = vtepNetworkPortId(toProto(networkId))
            storage.exists(classOf[Port], pId).await() shouldBe true
        }
    }

    "L2 Gateway Connection " should "add a peering entry for each existing" +
                                    "RemoteMacEntry" in {
        createTenantNetwork(10, networkId)
        createRouter(20, routerId)
        val gwDevId = createGatewayDevice(30)

        val rm1 = createRemoteMacEntry(40, gwDevId, 100)
        val rm2 = createRemoteMacEntry(50, gwDevId, 100)
        val rm3 = createRemoteMacEntry(60, gwDevId, 101)

        val cnxnId = createL2GatewayConnection(70, gwDevId)
        eventually {
            checkL2GatewayConnection(cnxnId, gwDevId, Seq(rm1, rm2))
        }
    }

    private def checkL2GatewayConnection(cnxnId: UUID,
                                         gwDevId: UUID,
                                         rms: Seq[RemoteMac],
                                         nwId: UUID = networkId,
                                         rtrId: UUID = routerId): Unit = {
        val nwPortId = vtepNetworkPortId(toProto(networkId))
        val rtrPortId = vtepRouterPortId(toProto(networkId))
        val rmIds = rms.map(_.id)

        val gwDevFtr = storage.get(classOf[GatewayDevice], gwDevId)
        val cnxnFtr = storage.get(classOf[L2GatewayConnection], cnxnId)
        val nwPortFtr = storage.get(classOf[Port], nwPortId)
        val rtrPortFtr = storage.get(classOf[Port], rtrPortId)
        val rmesFtr = storage.getAll(classOf[RemoteMacEntry], rmIds)

        val gwDev = gwDevFtr.await()
        val cnxn = cnxnFtr.await()
        val nwPort = nwPortFtr.await()
        val rtrPort = rtrPortFtr.await()
        val rmes = rmesFtr.await()

        nwPort.getAdminStateUp shouldBe true
        nwPort.getNetworkId.asJava shouldBe networkId
        nwPort.getPeerId shouldBe rtrPort.getId
        nwPort.hasPortAddress shouldBe false
        nwPort.hasPortMac shouldBe false
        nwPort.getRemoteMacEntryIdsCount shouldBe 0

        rtrPort.getAdminStateUp shouldBe true
        rtrPort.getPeerId shouldBe nwPort.getId
        rtrPort.getRouteIdsCount shouldBe 0
        rtrPort.getRouterId.asJava shouldBe rtrId
        rtrPort.getTunnelIp shouldBe gwDev.getTunnelIps(0)
        rtrPort.getVni shouldBe cnxn.getSegmentationId
        rtrPort.getRemoteMacEntryIdsList.map {
            id => UUIDUtil.fromProto(id)
        } should contain theSameElementsAs rmIds

//        val peeringTable =
//            backend.stateTableStore.routerPortPeeringTable(rtrPortId.asJava)
//        for (rm <- rms) {
//            peeringTable.containsPersistent(rm.mac, rm.ip) shouldBe true
//        }
    }
}