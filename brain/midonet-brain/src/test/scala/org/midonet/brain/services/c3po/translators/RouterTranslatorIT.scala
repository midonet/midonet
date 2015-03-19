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
package org.midonet.brain.services.c3po.translators

import java.util.UUID

import org.junit.runner.RunWith
import org.midonet.brain.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.{Port, Route, Router}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class RouterTranslatorIT extends C3POMinionTestBase {
    it should "handle router CRUD" in {
        val r1Id = UUID.randomUUID()
        val r1Json = routerJson("router1", r1Id)
        executeSqlStmts(insertTaskSql(2, Create, RouterType,
                                      r1Json.toString, r1Id, "tx1"))

        val r1 = eventually(storage.get(classOf[Router], r1Id).await())
        UUIDUtil.fromProto(r1.getId) shouldBe r1Id
        r1.getName shouldBe "router1"
        r1.getAdminStateUp shouldBe true
        r1.getInboundFilterId should not be null
        r1.getOutboundFilterId should not be null

        val r1Chains = getChains(r1.getInboundFilterId, r1.getOutboundFilterId)
        r1Chains.inChain.getRuleIdsCount shouldBe 0
        r1Chains.outChain.getRuleIdsCount shouldBe 0

        val r2Id = UUID.randomUUID()
        val r2Json = routerJson("router2", r2Id, adminStateUp = false)
        val r1JsonV2 = routerJson("router1", r1Id, tenantId = "new-tenant")
        executeSqlStmts(insertTaskSql(3, Create, RouterType,
                                      r2Json.toString, r2Id, "tx2"),
                        insertTaskSql(4, Update, RouterType,
                                      r1JsonV2.toString, r1Id, "tx2"))

        val r2 = eventually(storage.get(classOf[Router], r2Id).await())
        r2.getName shouldBe "router2"

        eventually {
            val r1v2 = storage.get(classOf[Router], r1Id).await()
            r1v2.getTenantId shouldBe "new-tenant"

            // Chains should be preserved.
            r1v2.getInboundFilterId shouldBe r1.getInboundFilterId
            r1v2.getOutboundFilterId shouldBe r1.getOutboundFilterId
        }

        executeSqlStmts(
            insertTaskSql(5, Delete, RouterType, null, r1Id, "tx3"),
            insertTaskSql(6, Delete, RouterType, null, r2Id, "tx3"))

        eventually {
            storage.getAll(classOf[Router]).await().size shouldBe 0
        }
    }

    it should "handle router gateway CRUD" in {
        val tenant = "tenant1"

        val nwId = UUID.randomUUID()
        val nwJson = networkJson(nwId, tenant, name = "tenant-network",
                                 external = true)

        val snId = UUID.randomUUID()
        val snJson = subnetJson(snId, nwId, tenant,
                                cidr = "10.0.1.0/24", gatewayIp = "10.0.1.1")

        val gwIpAddr = "10.0.0.1"
        val prGwPortId = UUID.randomUUID()
        val gwIpAlloc = IPAlloc(gwIpAddr, snId.toString)
        val prGwPortJson = portJson("pr-tr-gw-port", prGwPortId, nwId,
                                    fixedIps = List(gwIpAlloc),
                                    deviceId = prGwPortId,
                                    deviceOwner = DeviceOwner.ROUTER_GATEWAY)

        val trId = UUID.randomUUID()
        val trJson = routerJson("tenant-router", trId, gwPortId = prGwPortId)

        val prId = RouterTranslator.providerRouterId

        executeSqlStmts(insertTaskSql(2, Create, NetworkType,
                                      nwJson.toString, nwId, "tx1"),
                        insertTaskSql(3, Create, SubnetType,
                                      snJson.toString, snId, "tx2"),
                        insertTaskSql(4, Create, PortType,
                                      prGwPortJson.toString, prGwPortId, "tx3"),
                        insertTaskSql(5, Create, RouterType,
                                      trJson.toString, trId, "tx3"))

        val tr = eventually(storage.get(classOf[Router], trId).await())
        tr.getPortIdsCount shouldBe 1
        tr.getRouteIdsCount shouldBe 0

        validateGateway(tr, prGwPortId, gwIpAddr)

        // Rename router and make sure everything is preserved.
        val trV2Json = routerJson("tenant-router-v2", trId).toString
        executeSqlStmts(insertTaskSql(6, Update, RouterType,
                                      trV2Json, trId, "tx4"))
        val trV2 = eventually {
            val trRenamed = storage.get(classOf[Router], trId).await()
            trRenamed.getName shouldBe "tenant-router-v2"
            trRenamed
        }
        trV2.getPortIdsCount shouldBe 1
        validateGateway(trV2, prGwPortId, gwIpAddr)

        // Delete Gateway.
        executeSqlStmts(insertTaskSql(7, Delete, PortType,
                                      null, prGwPortId, "tx5"))
        eventually {
            storage.exists(classOf[Port], prGwPortId).await() shouldBe false
        }

        // Should delete tenant gateway port as well.
        val trGwPortId = RouterTranslator.tenantGwPortId(prGwPortId)
        storage.exists(classOf[Port], trGwPortId).await() shouldBe false

        val List(prV3, trV3) =
            storage.getAll(classOf[Router], List(prId, trId)).map(_.await())
        prV3.getPortIdsCount shouldBe 0
        trV3.getPortIdsCount shouldBe 0

        // All routes should be deleted.
        storage.getAll(classOf[Route]).await() shouldBe empty

        // Re-add gateway.
        executeSqlStmts(insertTaskSql(8, Create, PortType,
                                      prGwPortJson.toString, prGwPortId, "tx6"),
                        insertTaskSql(9, Update, RouterType,
                                      trJson.toString, trId, "tx6"))
        val trV4 = eventually {
            val trV4 = storage.get(classOf[Router], trId).await()
            trV4.getPortIdsCount shouldBe 1
            trV4
        }

        validateGateway(trV4, prGwPortId, gwIpAddr)

        // Delete the gateway and then router.
        executeSqlStmts(insertTaskSql(10, Delete, PortType,
                                      null, prGwPortId, "tx7"),
                        insertTaskSql(11, Delete, RouterType,
                                      null, trId, "tx7"))

        eventually {
            storage.exists(classOf[Router], trId).await() shouldBe false
        }
        storage.getAll(classOf[Route]).await() shouldBe empty
        val prV5 = storage.get(classOf[Router], prId).await()
        prV5.getPortIdsCount shouldBe 0
    }

    private def validateGateway(tr: Router, prGwPortId: UUID,
                                gwIpAddr: String): Unit = {
        // Tenant router should have gateway port.
        val trGwPortId = RouterTranslator.tenantGwPortId(prGwPortId)
        tr.getPortIdsList.asScala should contain(trGwPortId)

        // Get gateway ports on tenant and provider routers.
        val portFs = storage.getAll(classOf[Port], List(prGwPortId, trGwPortId))

        // Get routes.
        val prLocalRtId = RouteManager.localRouteId(prGwPortId)
        val prGwRtId = RouteManager.gatewayRouteId(prGwPortId)
        val trLocalRtId = RouteManager.localRouteId(trGwPortId)
        val trGwRtId = RouteManager.gatewayRouteId(trGwPortId)

        val List(prLocalRt, prGwRt, trLocalRt, trGwRt) =
            List(prLocalRtId, prGwRtId, trLocalRtId, trGwRtId)
                .map(storage.get(classOf[Route], _)).map(_.await())

        val List(prGwPort, trGwPort) = portFs.map(_.await())

        // Check ports have correct router and route IDs.
        prGwPort.getRouterId shouldBe RouterTranslator.providerRouterId
        prGwPort.getRouteIdsList.asScala should
        contain only (prGwRtId, prLocalRtId)
        trGwPort.getRouterId shouldBe tr.getId
        trGwPort.getRouteIdsList.asScala should
        contain only (trGwRtId, trLocalRtId)

        // Ports should be linked.
        prGwPort.getPeerId shouldBe trGwPortId
        trGwPort.getPeerId shouldBe prGwPort.getId

        prGwPort.getPortAddress shouldBe PortManager.LL_GW_IP_1
        trGwPort.getPortAddress.getAddress shouldBe gwIpAddr

        validateLocalRoute(prLocalRt, prGwPort)
        validateLocalRoute(trLocalRt, trGwPort)

        prGwRt.getNextHop shouldBe NextHop.PORT
        prGwRt.getNextHopPortId shouldBe prGwPort.getId
        prGwRt.getDstSubnet shouldBe
        IPSubnetUtil.fromAddr(trGwPort.getPortAddress)
        prGwRt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4

        trGwRt.getNextHop shouldBe NextHop.PORT
        trGwRt.getNextHopPortId shouldBe trGwPort.getId
        trGwRt.getDstSubnet shouldBe IPSubnetUtil.univSubnet4
        trGwRt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
    }

    private def validateLocalRoute(rt: Route, nextHopPort: Port): Unit = {
        rt.getNextHop shouldBe NextHop.LOCAL
        rt.getNextHopPortId shouldBe nextHopPort.getId
        rt.getDstSubnet shouldBe
        IPSubnetUtil.fromAddr(nextHopPort.getPortAddress)
        rt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        rt.getWeight shouldBe RouteManager.DEFAULT_WEIGHT
    }


}
