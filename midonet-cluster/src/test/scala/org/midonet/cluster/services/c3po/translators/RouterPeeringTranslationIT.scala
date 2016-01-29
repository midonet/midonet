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

    private def updateGatewayDevice(taskId: Int, id: UUID,
                                    routerId: UUID = routerId,
                                    tunnelIps: Seq[String] = Seq("30.0.0.1"))
    = {
        val json = gatewayDeviceJson(resourceId = routerId, id = id,
            tunnelIps = tunnelIps)
        insertUpdateTask(taskId, GatewayDeviceType, json, id)
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

    "L2 Gateway Connection with no devices" should "be created" in {

        createTenantNetwork(10, networkId)
        createRouter(20, routerId)
        val gwDevId = createGatewayDevice(30)
        createL2GatewayConnection(40, gwDevId)

        eventually {
            val pId = vtepNetworkPortId(toProto(networkId))
            storage.exists(classOf[Port], pId).await() shouldBe true
        }
    }

    "L2 Gateway Connection" should "add a peering entry for each existing " +
                                   "RemoteMacEntry" in {
        createTenantNetwork(10, networkId)
        createRouter(20, routerId)
        val gwDevId = createGatewayDevice(30)

        val rm1 = createRemoteMacEntry(40, gwDevId, 100)
        val rm2 = createRemoteMacEntry(50, gwDevId, 100)
        val rm3 = createRemoteMacEntry(60, gwDevId, 101)

        // Should only use RMEs with same VNI, i.e. rm1 and rm2.
        val cnxnId = createL2GatewayConnection(70, gwDevId)
        eventually(checkL2GatewayConnection(cnxnId, gwDevId, Seq(rm1, rm2)))

        // Has VNI = 101, so should only use rm3.
        val nw2Id = createTenantNetwork(80)
        val cnxn2Id = createL2GatewayConnection(90, gwDevId,
                                                networkId = nw2Id, vni = 101)
        eventually(checkL2GatewayConnection(cnxn2Id, gwDevId, Seq(rm3),
                                            networkId = nw2Id))

        insertDeleteTask(100, L2ConnType, cnxnId)
        eventually(checkNoL2GatewayConnection())

        insertDeleteTask(110, L2ConnType, cnxn2Id)
        eventually(checkNoL2GatewayConnection(nw2Id))
    }

    "Remote MAC entry CRUD" should "be reflected in peering tables." in {
        createRouter(10, routerId)
        val gwDevId = createGatewayDevice(20)

        createTenantNetwork(30, networkId)
        val nw2Id = createTenantNetwork(40)
        val nw3Id = createTenantNetwork(50)

        val cnxn1Id = createL2GatewayConnection(60, gwDevId)
        val cnxn2Id = createL2GatewayConnection(70, gwDevId, networkId = nw2Id)
        val cnxn3Id = createL2GatewayConnection(80, gwDevId, networkId = nw3Id,
                                                vni = 101)

        eventually {
            checkL2GatewayConnection(cnxn1Id, gwDevId, Seq())
            checkL2GatewayConnection(cnxn2Id, gwDevId, Seq(), networkId = nw2Id)
            checkL2GatewayConnection(cnxn3Id, gwDevId, Seq(), networkId = nw3Id)
        }

        val rm1 = createRemoteMacEntry(90, gwDevId, 100)
        val rm2 = createRemoteMacEntry(100, gwDevId, 100)
        val rm3 = createRemoteMacEntry(110, gwDevId, 101)
        createRemoteMacEntry(120, gwDevId, 102)

        eventually {
            checkL2GatewayConnection(cnxn1Id, gwDevId, Seq(rm1, rm2))
            checkL2GatewayConnection(cnxn2Id, gwDevId, Seq(rm1, rm2),
                                     networkId = nw2Id)
            checkL2GatewayConnection(cnxn3Id, gwDevId, Seq(rm3),
                                     networkId = nw3Id)
        }

        // Deleting rme1 should remove it from peering tables for both cnxn1
        // and cnxn2
        insertDeleteTask(130, RemoteMacEntryType, rm1.id)
        eventually {
            checkL2GatewayConnection(cnxn1Id, gwDevId, Seq(rm2))
            checkL2GatewayConnection(cnxn2Id, gwDevId, Seq(rm2),
                                     networkId = nw2Id)
            checkL2GatewayConnection(cnxn3Id, gwDevId, Seq(rm3),
                                     networkId = nw3Id)
        }

        // Deleting cnxn1 should leave cnxn2's peering table unaffected.
        insertDeleteTask(140, L2ConnType, cnxn1Id)
        eventually {
            checkNoL2GatewayConnection(networkId)
            checkL2GatewayConnection(cnxn2Id, gwDevId, Seq(rm2),
                                     networkId = nw2Id)
            checkL2GatewayConnection(cnxn3Id, gwDevId, Seq(rm3),
                                     networkId = nw3Id)

            val Seq(rme2, rme3) = storage.getAll(classOf[RemoteMacEntry],
                                                 Seq(rm2.id, rm3.id)).await()
            rme2.getPortIdsList should contain only
                vtepRouterPortId(toProto(nw2Id))
            rme3.getPortIdsList should contain only
                vtepRouterPortId(toProto(nw3Id))
        }

        // Delete the remaining RMEs.
        insertDeleteTask(150, L2ConnType, cnxn2Id)
        insertDeleteTask(160, L2ConnType, cnxn3Id)
        eventually {
            checkNoL2GatewayConnection(networkId)
            checkNoL2GatewayConnection(nw2Id)
            checkNoL2GatewayConnection(nw3Id)
        }

        // Deleting the GatewayDevice should delete the remaining RMEs
        Seq(rm2.id, rm3.id).map(storage.exists(classOf[RemoteMacEntry], _))
            .map(_.await()) shouldBe Seq(true, true)
        insertDeleteTask(170, GatewayDeviceType, gwDevId)
        eventually {
            Seq(rm2.id, rm3.id).map(storage.exists(classOf[RemoteMacEntry], _))
                .map(_.await()) shouldBe Seq(false, false)
        }
    }

    "Updating Gateway Device" should "update corresponding router port" in {
        val nId = createTenantNetwork(10)
        val rId = createRouter(20)
        val gwDevId = createGatewayDevice(30, routerId = rId,
                                              tunnelIps = Seq("1.1.1.1"))
        createL2GatewayConnection(40, gwDevId, networkId = nId)

        def checkVtepRouterPortTunnelIp(nwId: UUID, ip: String) = {
            eventually {
                val pId = vtepRouterPortId(nwId)
                val port = storage.get(classOf[Port], pId).await()
                port.getTunnelIp.getAddress shouldBe ip
            }
        }

        checkVtepRouterPortTunnelIp(nId, "1.1.1.1")

        val n2Id = createTenantNetwork(50)
        val r2Id = createRouter(60)
        val gwDev2Id = createGatewayDevice(70, routerId = r2Id,
                                               tunnelIps = Seq("1.1.1.2"))
        createL2GatewayConnection(80, gwDev2Id, networkId = n2Id)

        checkVtepRouterPortTunnelIp(n2Id, "1.1.1.2")

        updateGatewayDevice(90, gwDevId, rId, Seq("1.1.1.3"))

        checkVtepRouterPortTunnelIp(nId, "1.1.1.3")
        checkVtepRouterPortTunnelIp(n2Id, "1.1.1.2")
    }

    private def checkNoL2GatewayConnection(networkId: UUID = networkId)
    : Unit = {
        val nwPortId = vtepNetworkPortId(toProto(networkId))
        val rtrPortId = vtepRouterPortId(toProto(networkId))
        Seq(storage.exists(classOf[Port], nwPortId),
            storage.exists(classOf[Port], rtrPortId))
            .map(_.await()) shouldBe Seq(false, false)

        val peeringTablePath =
            backend.stateTableStore.routerPortPeeringTablePath(rtrPortId.asJava)
        curator.checkExists().forPath(peeringTablePath) shouldBe null
    }

    private def checkL2GatewayConnection(cnxnId: UUID,
                                         gwDevId: UUID,
                                         rms: Seq[RemoteMac],
                                         networkId: UUID = networkId,
                                         routerId: UUID = routerId): Unit = {
        val nwPortId = vtepNetworkPortId(toProto(networkId))
        val rtrPortId = vtepRouterPortId(toProto(networkId))
        val rmIds = rms.map(_.id)

        val gwDevFtr = storage.get(classOf[GatewayDevice], gwDevId)
        val cnxnFtr = storage.get(classOf[L2GatewayConnection], cnxnId)
        val nwPortFtr = storage.get(classOf[Port], nwPortId)
        val rtrPortFtr = storage.get(classOf[Port], rtrPortId)

        val gwDev = gwDevFtr.await()
        val cnxn = cnxnFtr.await()
        val nwPort = nwPortFtr.await()
        val rtrPort = rtrPortFtr.await()

        nwPort.getAdminStateUp shouldBe true
        nwPort.getNetworkId.asJava shouldBe networkId
        nwPort.getPeerId shouldBe rtrPort.getId
        nwPort.hasPortAddress shouldBe false
        nwPort.hasPortMac shouldBe false
        nwPort.getRemoteMacEntryIdsCount shouldBe 0

        rtrPort.getAdminStateUp shouldBe true
        rtrPort.getPeerId shouldBe nwPort.getId
        rtrPort.getRouteIdsCount shouldBe 0
        rtrPort.getRouterId.asJava shouldBe routerId
        rtrPort.getTunnelIp shouldBe gwDev.getTunnelIps(0)
        rtrPort.getVni shouldBe cnxn.getSegmentationId
        rtrPort.getRemoteMacEntryIdsList.map {
            id => UUIDUtil.fromProto(id)
        } should contain theSameElementsAs rmIds

        val peeringTable =
            backend.stateTableStore.routerPortPeeringTable(rtrPortId.asJava)
        peeringTable.remoteSnapshot should
            contain theSameElementsAs rms.map(rm => (rm.mac, rm.ip))
    }
}