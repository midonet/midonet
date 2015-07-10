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

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Port => PortType, Router => RouterType}
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.Rule.{Action, FragmentPolicy}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.RouterTranslator.tenantGwPortId
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.midolman.state.Ip4ToMacReplicatedMap
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.{ICMP, MAC}
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class RouterTranslatorIT extends C3POMinionTestBase {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    it should "handle router CRUD" in {
        val r1Id = UUID.randomUUID()
        val r1Json = routerJson(r1Id, name = "router1")
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
        val r2Json = routerJson(r2Id, name = "router2", adminStateUp = false)
        val r1JsonV2 = routerJson(r1Id, tenantId = "new-tenant")
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
        val uplNetworkId = UUID.randomUUID()
        val uplNwSubnetId = UUID.randomUUID()
        val uplNwDhcpPortId = UUID.randomUUID()

        val extNwId = UUID.randomUUID()
        val extNwSubnetId = UUID.randomUUID()
        val extNwDhcpPortId = UUID.randomUUID()

        val hostId = UUID.randomUUID()

        val edgeRtrId = UUID.randomUUID()
        val edgeRtrUplNwIfPortId = UUID.randomUUID()
        val edgeRtrExtNwIfPortId = UUID.randomUUID()

        val tntRtrId = UUID.randomUUID()
        val extNwGwPortId = UUID.randomUUID()

        // Create uplink network.
        createUplinkNetwork(2, uplNetworkId)
        createSubnet(3, uplNwSubnetId, uplNetworkId, "10.0.0.0/16")
        createDhcpPort(4, uplNwDhcpPortId, uplNetworkId,
                       uplNwSubnetId, "10.0.0.1")

        createHost(hostId)

        // Create edge router.
        createRouter(5, edgeRtrId)
        createRouterInterfacePort(6, edgeRtrUplNwIfPortId, uplNetworkId,
                                  edgeRtrId, "10.0.0.2", "02:02:02:02:02:02",
                                  uplNwSubnetId, hostId = hostId,
                                  ifName = "eth0")
        createRouterInterface(7, edgeRtrId, edgeRtrUplNwIfPortId, uplNwSubnetId)

        // Create external network.
        createTenantNetwork(8, extNwId, external = true)
        createSubnet(9, extNwSubnetId, extNwId, "10.0.1.0/24")
        createDhcpPort(10, extNwDhcpPortId, extNwId,
                       extNwSubnetId, "10.0.1.0")
        createRouterInterfacePort(11, edgeRtrExtNwIfPortId, extNwId, edgeRtrId,
                                  "10.0.1.2", "03:03:03:03:03:03",
                                  extNwSubnetId)
        createRouterInterface(12, edgeRtrId,
                              edgeRtrExtNwIfPortId, extNwSubnetId)
        val extNwArpTable = dataClient.getIp4MacMap(extNwId)

        // Sanity check for external network's connection to edge router. This
        // is just a normal router interface, so RouterInterfaceTranslatorIT
        // checks the details.
        eventually {
            val nwPort = storage.get(classOf[Port],
                                     edgeRtrExtNwIfPortId).await()
            val rPortF = storage.get(classOf[Port], nwPort.getPeerId)
            val rtrF = storage.get(classOf[Router], edgeRtrId)
            val (rPort, rtr) = (rPortF.await(), rtrF.await())
            rPort.getRouterId shouldBe rtr.getId
            rPort.getPortAddress.getAddress shouldBe "10.0.1.2"
            rPort.getPortMac shouldBe "03:03:03:03:03:03"
            rtr.getPortIdsCount shouldBe 2
            rtr.getPortIdsList.asScala should contain(rPort.getId)
            // Start the replicated map here.
            extNwArpTable.start()
        }

        // Create tenant router and check gateway setup.
        createRouterGatewayPort(13, extNwGwPortId, extNwId, tntRtrId,
                                "10.0.1.3", "ab:cd:ef:00:00:03", extNwSubnetId)
        createRouter(14, tntRtrId, gwPortId = extNwGwPortId, enableSnat = true)
        validateGateway(tntRtrId, extNwGwPortId, "10.0.1.3",
                        "ab:cd:ef:00:00:03", "10.0.1.2", snatEnabled = true,
                        extNwArpTable)

        // Rename router to make sure update doesn't break anything.
        val trRenamedJson = routerJson(tntRtrId, name = "tr-renamed",
                                       gwPortId = extNwGwPortId,
                                       enableSnat = true).toString
        insertUpdateTask(15, RouterType, trRenamedJson, tntRtrId)
        eventually {
            val tr = storage.get(classOf[Router], tntRtrId).await()
            tr.getName shouldBe "tr-renamed"
            tr.getPortIdsCount shouldBe 1
            validateGateway(tntRtrId, extNwGwPortId, "10.0.1.3",
                            "ab:cd:ef:00:00:03", "10.0.1.2", snatEnabled = true,
                            extNwArpTable)
        }

        // Delete gateway.
        insertDeleteTask(16, PortType, extNwGwPortId)
        eventually {
            val trF = storage.get(classOf[Router], tntRtrId)
            val extNwF = storage.get(classOf[Network], extNwId)
            val (tr, extNw) = (trF.await(), extNwF.await())
            tr.getPortIdsCount shouldBe 0
            extNw.getPortIdsList.asScala should contain only (
                UUIDUtil.toProto(edgeRtrExtNwIfPortId),
                UUIDUtil.toProto(extNwDhcpPortId))
            extNwArpTable.containsKey("10.0.1.3") shouldBe false
            validateNatRulesDeleted(tntRtrId)
        }

        // Re-add gateway.
        createRouterGatewayPort(17, extNwGwPortId, extNwId, tntRtrId,
                                "10.0.1.4", "ab:cd:ef:00:00:04", extNwSubnetId)
        val trAddGwJson = routerJson(tntRtrId, name = "tr-add-gw",
                                     gwPortId = extNwGwPortId,
                                     enableSnat = true).toString
        insertUpdateTask(18, RouterType, trAddGwJson, tntRtrId)
        eventually {
            validateGateway(tntRtrId, extNwGwPortId, "10.0.1.4",
                            "ab:cd:ef:00:00:04", "10.0.1.2", snatEnabled = true,
                            extNwArpTable)
        }

        // Disable SNAT.
        val trDisableSnatJson = routerJson(tntRtrId, name = "tr-disable-snat",
                                           gwPortId = extNwGwPortId,
                                           enableSnat = false).toString
        insertUpdateTask(19, RouterType, trDisableSnatJson, tntRtrId)
        eventually {
            validateGateway(tntRtrId, extNwGwPortId, "10.0.1.4",
                            "ab:cd:ef:00:00:04", "10.0.1.2",
                            snatEnabled = false, extNwArpTable)
        }

        // Re-enable SNAT.
        insertUpdateTask(20, RouterType, trAddGwJson, tntRtrId)
        eventually {
            validateGateway(tntRtrId, extNwGwPortId, "10.0.1.4",
                            "ab:cd:ef:00:00:04", "10.0.1.2", snatEnabled = true,
                            extNwArpTable)
        }

        // Delete gateway and router.
        insertDeleteTask(21, PortType, extNwGwPortId)
        insertDeleteTask(22, RouterType, tntRtrId)
        eventually {
            val extNwF = storage.get(classOf[Network], extNwId)
            List(storage.exists(classOf[Router], tntRtrId),
                 storage.exists(classOf[Port], extNwGwPortId),
                 storage.exists(classOf[Port], tenantGwPortId(extNwGwPortId)))
                .map(_.await()) shouldBe List(false, false, false)
            val extNw = extNwF.await()
            extNw.getPortIdsList.asScala should contain only (
                UUIDUtil.toProto(edgeRtrExtNwIfPortId),
                UUIDUtil.toProto(extNwDhcpPortId))
            extNwArpTable.containsKey("10.0.1.4") shouldBe false
        }
        extNwArpTable.stop()
    }

    private def validateGateway(rtrId: UUID, nwGwPortId: UUID,
                                gatewayIp: String, trPortMac: String,
                                nextHopIp: String, snatEnabled: Boolean,
                                extNwArpTable: Ip4ToMacReplicatedMap): Unit = {
        // Tenant router should have gateway port and no routes.
        val trGwPortId = tenantGwPortId(nwGwPortId)
        val tr = eventually {
            val tr = storage.get(classOf[Router], rtrId).await()
            tr.getPortIdsList.asScala should contain only trGwPortId
            tr.getRouteIdsCount shouldBe 0

            // The ARP entry should be added to the external network ARP table.
            extNwArpTable.get(gatewayIp) shouldBe MAC.fromString(trPortMac)
            tr
        }

        // Get the router gateway port and its peer on the network.
        val portFs = storage.getAll(classOf[Port], List(nwGwPortId, trGwPortId))

        // Get routes on router gateway port.
        val trLocalRtId = RouteManager.localRouteId(trGwPortId)
        val trGwRtId = RouteManager.gatewayRouteId(trGwPortId)

        val List(trLocalRt, trGwRt) =
            List(trLocalRtId, trGwRtId)
                .map(storage.get(classOf[Route], _)).map(_.await())

        val List(nwGwPort, trGwPort) = portFs.await()

        // Check router port has correct router and route IDs.
        trGwPort.getRouterId shouldBe UUIDUtil.toProto(rtrId)
        trGwPort.getRouteIdsList.asScala should
            contain only (trGwRtId, trLocalRtId)

        // Network port has no routes.
        nwGwPort.getRouteIdsCount shouldBe 0

        // Ports should be linked.
        nwGwPort.getPeerId shouldBe trGwPortId
        trGwPort.getPeerId shouldBe nwGwPort.getId

        trGwPort.getPortAddress.getAddress shouldBe gatewayIp
        trGwPort.getPortMac shouldBe trPortMac

        validateLocalRoute(trLocalRt, trGwPort)

        trGwRt.getNextHop shouldBe NextHop.PORT
        trGwRt.getNextHopPortId shouldBe trGwPort.getId
        trGwRt.getDstSubnet shouldBe IPSubnetUtil.univSubnet4
        trGwRt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        trGwRt.getNextHopGateway.getAddress shouldBe nextHopIp

        if (snatEnabled)
            validateGatewayNatRules(tr, gatewayIp, trGwPortId)
    }

    private def validateLocalRoute(rt: Route, nextHopPort: Port): Unit = {
        rt.getNextHop shouldBe NextHop.LOCAL
        rt.getNextHopPortId shouldBe nextHopPort.getId
        rt.getDstSubnet shouldBe
        IPSubnetUtil.fromAddr(nextHopPort.getPortAddress)
        rt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        rt.getWeight shouldBe RouteManager.DEFAULT_WEIGHT
    }

    private def validateGatewayNatRules(tr: Router, gatewayIp: String,
                                        trGwPortId: Commons.UUID): Unit = {
        val chainIds = List(tr.getInboundFilterId, tr.getOutboundFilterId)
        val List(inChain, outChain) =
            storage.getAll(classOf[Chain], chainIds).await()
        inChain.getRuleIdsCount shouldBe 2
        outChain.getRuleIdsCount shouldBe 2

        val ruleIds = inChain.getRuleIdsList.asScala.toList ++
                      outChain.getRuleIdsList.asScala
        val List(inRevSnatRule, inDropWrongPortTrafficRule,
                 outSnatRule, outDropFragmentsRule) =
            storage.getAll(classOf[Rule], ruleIds).await()

        val gwSubnet = IPSubnetUtil.fromAddr(gatewayIp)

        outSnatRule.getChainId shouldBe outChain.getId
        outSnatRule.getOutPortIdsList.asScala should contain only trGwPortId
        outSnatRule.getNwSrcIp shouldBe gwSubnet
        outSnatRule.getNwSrcInv shouldBe true
        validateNatRule(outSnatRule, dnat = false, gatewayIp)

        val odfr = outDropFragmentsRule
        odfr.getChainId shouldBe outChain.getId
        odfr.getOutPortIdsList should contain only trGwPortId
        odfr.getNwSrcIp.getAddress shouldBe gatewayIp
        odfr.getNwSrcInv shouldBe true
        odfr.getType shouldBe Rule.Type.LITERAL_RULE
        odfr.getFragmentPolicy shouldBe FragmentPolicy.ANY
        odfr.getAction shouldBe Action.DROP

        inRevSnatRule.getChainId shouldBe inChain.getId
        inRevSnatRule.getNwDstIp shouldBe gwSubnet
        validateNatRule(inRevSnatRule, dnat = false, addr = null)

        val idwptr = inDropWrongPortTrafficRule
        idwptr.getChainId shouldBe inChain.getId
        idwptr.getNwDstIp shouldBe gwSubnet
        idwptr.getType shouldBe Rule.Type.LITERAL_RULE
        idwptr.getNwProto shouldBe ICMP.PROTOCOL_NUMBER
        idwptr.getAction shouldBe Action.DROP
    }

    // addr == null for reverse NAT rule
    private def validateNatRule(r: Rule, dnat: Boolean, addr: String): Unit = {
        r.getType shouldBe Rule.Type.NAT_RULE

        val data = r.getNatRuleData
        data.getDnat shouldBe dnat
        data.getReverse shouldBe (addr == null)

        if (addr != null) {
            data.getNatTargetsCount shouldBe 1
            data.getNatTargets(0).getTpStart shouldBe 1
            data.getNatTargets(0).getTpEnd shouldBe 0xffff
            data.getNatTargets(0).getNwStart.getAddress shouldBe addr
            data.getNatTargets(0).getNwEnd.getAddress shouldBe addr
        }
    }

    private def validateNatRulesDeleted(rtrId: UUID): Unit = {
        import RouterTranslator._
        val r = storage.get(classOf[Router], rtrId).await()
        val Seq(inChain, outChain) = storage.getAll(
            classOf[Chain],
            Seq(r.getInboundFilterId, r.getOutboundFilterId)).await()
        inChain.getRuleIdsCount shouldBe 0
        outChain.getRuleIdsCount shouldBe 0
        Seq(outSnatRuleId(rtrId), outDropUnmatchedFragmentsRuleId(rtrId),
            inReverseSnatRuleId(rtrId), inDropWrongPortTrafficRuleId(rtrId))
            .map(storage.exists(classOf[Rule], _).await()) shouldBe
            Seq(false, false, false, false)
    }
}
