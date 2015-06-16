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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.services.c3po.C3POStorageManager.Operation
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Route, Router, Rule}
import org.midonet.cluster.util.IPSubnetUtil.univSubnet4
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}

class FloatingIpTranslatorTestBase extends TranslatorTestBase with OpMatchers {
    protected var translator: FloatingIpTranslator = _

    // Floating IP data setup
    protected val fipId = randomUuidProto
    protected val fipRouterId = randomUuidProto
    protected val fipPortId = randomUuidProto
    protected val fipIpAddr = IPAddressUtil.toProto("10.10.10.1")
    protected val fipIpSubnet = IPSubnetUtil.fromAddr(fipIpAddr)
    protected val fipFixedIpAddr = IPAddressUtil.toProto("192.168.1.1")
    protected val fipFixedIpSubnet = IPSubnetUtil.fromAddr(fipFixedIpAddr)
    protected val fipRouteId = RouteManager.fipGatewayRouteId(fipId)

    protected val unboundFip = nFloatingIpFromTxt(s"""
        id { $fipId }
        floating_ip_address { $fipIpAddr }
        router_id { $fipRouterId }
        """)

    protected def fip(routerId: UUID = fipRouterId) =
        nFloatingIpFromTxt(s"""
            id { $fipId }
            floating_ip_address { $fipIpAddr }
            router_id { $routerId }
            port_id { $fipPortId }
            fixed_ip_address { $fipFixedIpAddr }
        """)
    protected val boundFip = fip()

    // Main tenant router setup
    protected val prvRouterGatewayPortId = randomUuidProto
    protected val fipRouterInternalPortId = randomUuidProto
    protected val fipRouterGatewayPortId = randomUuidProto
    protected val fipRouterInChainId = ChainManager.inChainId(fipRouterId)
    protected val fipRouterOutChainId = ChainManager.outChainId(fipRouterId)

    protected val fipRouter = mRouterFromTxt(s"""
        id { $fipRouterId }
        port_ids { $fipRouterInternalPortId }
        port_ids { $fipRouterGatewayPortId }
        inbound_filter_id { $fipRouterInChainId }
        outbound_filter_id { $fipRouterOutChainId }
        """)

    protected val fipNeutronRouterNoGwPort = nRouterFromTxt(s"""
        id { $fipRouterId }
        """)

    protected val fipNeutronRouter = nRouterFromTxt(s"""
        id { $fipRouterId }
        gw_port_id { $prvRouterGatewayPortId }
        """)

    // Routes and rules
    protected val gwRoute = mRouteFromTxt(s"""
        id { $fipRouteId }
        src_subnet { $univSubnet4 }
        dst_subnet { $fipIpSubnet }
        next_hop: PORT
        next_hop_port_id { $prvRouterGatewayPortId }
        weight: ${RouteManager.DEFAULT_WEIGHT}
        """)

    protected val snatRuleId = RouteManager.fipSnatRuleId(fipId)
    protected val dnatRuleId = RouteManager.fipDnatRuleId(fipId)

    protected def snatRule(oChainId: UUID, prvRouterGatewayPortId: UUID) =
        mRuleFromTxt(s"""
            id { $snatRuleId }
            chain_id { $oChainId }
            action: ACCEPT
            out_port_ids { $prvRouterGatewayPortId }
            nw_src_ip { $fipFixedIpSubnet }
            nat_rule_data {
                nat_targets {
                    nw_start { $fipIpAddr }
                    nw_end { $fipIpAddr }
                    tp_start: 1
                    tp_end: 65535
                }
                dnat: false
            }
        """)
    protected val snat = snatRule(fipRouterOutChainId, prvRouterGatewayPortId)

    protected def dnatRule(iChainId: UUID, prvRouterGatewayPortId: UUID) =
        mRuleFromTxt(s"""
            id { $dnatRuleId }
            chain_id { $iChainId }
            action: ACCEPT
            in_port_ids { $prvRouterGatewayPortId }
            nw_dst_ip { $fipIpSubnet }
            nat_rule_data {
                nat_targets {
                    nw_start { $fipFixedIpAddr }
                    nw_end { $fipFixedIpAddr }
                    tp_start: 1
                    tp_end: 65535
                }
                dnat: true
            }
        """)
    protected val dnat = dnatRule(fipRouterInChainId, prvRouterGatewayPortId)

    protected val inChainDummyRuleIds = """
        rule_ids { msb: 1 lsb: 2 }
        rule_ids { msb: 3 lsb: 4 }
        """
    protected val outChainDummyRuleIds = """
        rule_ids { msb: 5 lsb: 6 }
        rule_ids { msb: 7 lsb: 8 }
        """

    protected val fipRouterInChain = mChainFromTxt(s"""
        id { $fipRouterInChainId }
        $inChainDummyRuleIds
        """)

    protected val fipRouterOutChain = mChainFromTxt(s"""
        id { $fipRouterOutChainId }
        $outChainDummyRuleIds
        """)

    protected val inChainWithDnat = mChainFromTxt(s"""
        id { $fipRouterInChainId }
        rule_ids { $dnatRuleId }
        $inChainDummyRuleIds
        """)

    protected val outChainWithSnat = mChainFromTxt(s"""
        id { $fipRouterOutChainId }
        rule_ids { $snatRuleId }
        $outChainDummyRuleIds
        """)
}

/**
 * Tests a Neutron Floating IP Create translation.
 */
@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorCreateTest extends FloatingIpTranslatorTestBase {
    before {
        initMockStorage()
        translator = new FloatingIpTranslator(storage)

        bind(fipRouterInChainId, fipRouterInChain)
        bind(fipRouterOutChainId, fipRouterOutChain)
    }

    "Unassociated floating IP" should "not create anything" in {
        bind(fipRouterId, fipNeutronRouter)
        bind(fipRouterId, fipRouter)
        val midoOps = translator.translate(neutron.Create(unboundFip))

        midoOps shouldBe empty
    }

    "Associated floating IP" should "create a route to GW" in {
        bind(fipRouterId, fipNeutronRouter)
        bind(fipRouterId, fipRouter)
        val midoOps = translator.translate(neutron.Create(boundFip))

        midoOps should contain inOrderOnly (midonet.Create(gwRoute),
                                            midonet.Create(snat),
                                            midonet.Create(dnat),
                                            midonet.Update(inChainWithDnat),
                                            midonet.Update(outChainWithSnat))
    }

    "Tenant router for floating IP" should "throw an exception if it doesn't " +
    "have a gateway port assigned" in {
        bind(fipRouterId, fipNeutronRouterNoGwPort)
        bind(fipRouterId, fipRouter)

        val te = intercept[TranslationException] {
            translator.translate(neutron.Create(boundFip))
        }
        te.getCause should not be (null)
        te.getCause match {
            case ise: IllegalStateException if ise.getMessage != null =>
                ise.getMessage startsWith("No gateway port") shouldBe true
            case e => fail("Expected an IllegalStateException.", e)
        }
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorUpdateTest extends FloatingIpTranslatorTestBase {
    before {
        initMockStorage()
        translator = new FloatingIpTranslator(storage)
    }

    "Associating a floating IP to a port" should "create a gateway route" in {
        bind(fipId, unboundFip)
        bind(fipRouterId, fipNeutronRouter)
        bind(fipRouterId, fipRouter)
        bind(fipRouterInChainId, fipRouterInChain)
        bind(fipRouterOutChainId, fipRouterOutChain)
        val midoOps = translator.translate(neutron.Update(boundFip))

        midoOps should contain inOrderOnly (midonet.Create(gwRoute),
                                            midonet.Create(snat),
                                            midonet.Create(dnat),
                                            midonet.Update(inChainWithDnat),
                                            midonet.Update(outChainWithSnat))
    }

    "Associating a floating IP to a port" should "throw an exception if the " +
    "tenant router doesn't have a gateway port configured" in {
        bind(fipId, unboundFip)
        bind(fipRouterId, fipNeutronRouterNoGwPort)
        bind(fipRouterId, fipRouter)
        val te = intercept[TranslationException] {
                translator.translate(neutron.Update(boundFip))
        }

        te.getCause should not be (null)
        te.getCause match {
            case ise: IllegalStateException if ise.getMessage != null =>
                ise.getMessage startsWith("No gateway port") shouldBe true
            case e => fail("Expected an IllegalStateException.", e)
        }
    }

    "Removing a floating IP from a port" should "delete a GW route and " +
    "SNAT/DNAT rules, and remove the IDs fo those rules from the inbound / " +
    "outbound chains of the tenant router" in {
        bind(fipId, boundFip)
        bind(fipRouterId, fipNeutronRouter)
        bind(fipRouterId, fipRouter)
        bind(fipRouterInChainId, inChainWithDnat)
        bind(fipRouterOutChainId, outChainWithSnat)
        val midoOps = translator.translate(neutron.Update(unboundFip))

        midoOps should contain only (midonet.Delete(classOf[Route],
                                                    fipRouteId),
                                     midonet.Delete(classOf[Rule],
                                                    snatRuleId),
                                     midonet.Delete(classOf[Rule],
                                                    dnatRuleId),
                                     midonet.Update(fipRouterInChain),
                                     midonet.Update(fipRouterOutChain))
    }

    "UpdateOp that keeps the floating IP associated on the same router" should
    "keep the gateway route as is" in {
        bind(fipId, boundFip)
        bind(fipRouterId, fipNeutronRouter)
        bind(fipRouterId, fipRouter)
        val midoOps = translator.translate(neutron.Update(boundFip))

        midoOps shouldBe empty
    }

    // Secondary tenant router setup
    protected val fipRouter2Id = randomUuidProto
    protected val prvRouterGatewayPort2Id = randomUuidProto
    protected val fipRouter2InChainId = ChainManager.inChainId(fipRouter2Id)
    protected val fipRouter2OutChainId = ChainManager.outChainId(fipRouter2Id)
    protected val movedFip = fip(fipRouter2Id)
    protected val snat2 = snatRule(fipRouter2OutChainId, prvRouterGatewayPort2Id)
    protected val dnat2 = dnatRule(fipRouter2InChainId, prvRouterGatewayPort2Id)

    protected val fipNeutronRouter2 = nRouterFromTxt(s"""
        id { $fipRouter2Id }
        gw_port_id { $prvRouterGatewayPort2Id }
        """)

    protected val fipRouter2 = mRouterFromTxt(s"""
        id { $fipRouter2Id }
        inbound_filter_id { $fipRouter2InChainId }
        outbound_filter_id { $fipRouter2OutChainId }
        """)


    protected val fipRouter2InChain = mChainFromTxt(s"""
        id { $fipRouter2InChainId }
        $inChainDummyRuleIds
        """)

    protected val fipRouter2OutChain = mChainFromTxt(s"""
        id { $fipRouter2OutChainId }
        $outChainDummyRuleIds
        """)

    protected val fipRouter2InChainWithDnat = mChainFromTxt(s"""
        id { $fipRouter2InChainId }
        rule_ids { $dnatRuleId }
        $inChainDummyRuleIds
        """)

    protected val fipRouter2OutChainWithSnat = mChainFromTxt(s"""
        id { $fipRouter2OutChainId }
        rule_ids { $snatRuleId }
        $outChainDummyRuleIds
        """)

    "UpdateOp that moves the floating IP to a different router" should
    "delete the old NAT rules and create new ones on the new router" in {
        bind(fipId, boundFip)
        bind(fipRouter2Id, fipNeutronRouter2)
        bind(fipRouter2Id, fipRouter2)
        bind(fipRouterInChainId, inChainWithDnat)
        bind(fipRouterOutChainId, outChainWithSnat)
        bind(fipRouter2InChainId, fipRouter2InChain)
        bind(fipRouter2OutChainId, fipRouter2OutChain)
        val midoOps = translator.translate(neutron.Update(movedFip))

        midoOps should contain inOrderOnly (
                midonet.Delete(classOf[Rule], snatRuleId),
                midonet.Delete(classOf[Rule], dnatRuleId),
                midonet.Update(fipRouterInChain),
                midonet.Update(fipRouterOutChain),
                midonet.Create(snat2),
                midonet.Create(dnat2),
                midonet.Update(fipRouter2InChainWithDnat),
                midonet.Update(fipRouter2OutChainWithSnat))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorDeleteTest extends FloatingIpTranslatorTestBase {
    before {
        initMockStorage()
        translator = new FloatingIpTranslator(storage)
    }

    "Deleting an unassociated floating IP" should "not create anything" in {
        bind(fipId, unboundFip)
        val midoOps = translator.translate(neutron.Delete(classOf[FloatingIp],
                                                          fipId))

        midoOps shouldBe empty
    }

    "Deleting an associated floating IP" should "delete a GW route and " +
    "SNAT/DNAT rules, and remove the IDs fo those rules from the inbound / " +
    "outbound chains of the tenant router" in {
        bind(fipId, boundFip)
        bind(fipRouterInChainId, inChainWithDnat)
        bind(fipRouterOutChainId, outChainWithSnat)
        val midoOps = translator.translate(neutron.Delete(classOf[FloatingIp],
                                                          fipId))

        midoOps should contain only (midonet.Delete(classOf[Route],
                                                    fipRouteId),
                                     midonet.Delete(classOf[Rule],
                                                    snatRuleId),
                                     midonet.Delete(classOf[Rule],
                                                    dnatRuleId),
                                     midonet.Update(fipRouterInChain),
                                     midonet.Update(fipRouterOutChain))
    }
}