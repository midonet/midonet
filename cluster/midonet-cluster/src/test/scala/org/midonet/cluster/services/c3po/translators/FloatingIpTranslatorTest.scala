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
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Router, Rule}
import org.midonet.cluster.util.IPSubnetUtil.univSubnet4
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}

class FloatingIpTranslatorTestBase extends TranslatorTestBase with ChainManager
                                                              with OpMatchers {
    protected var translator: FloatingIpTranslator = _

    // Floating IP data setup
    protected val fipId = randomUuidProto
    protected val tntRouterId = randomUuidProto
    protected val fipPortId = randomUuidProto
    protected val fipIpAddr = IPAddressUtil.toProto("10.10.10.1")
    protected val fipIpSubnet = IPSubnetUtil.fromAddr(fipIpAddr)
    protected val fipFixedIpAddr = IPAddressUtil.toProto("192.168.1.1")
    protected val fipFixedIpSubnet = IPSubnetUtil.fromAddr(fipFixedIpAddr)

    protected val unboundFip = nFloatingIpFromTxt(s"""
        id { $fipId }
        floating_ip_address { $fipIpAddr }
        router_id { $tntRouterId }
        """)

    protected def fip(routerId: UUID = tntRouterId,
                      portId: UUID = fipPortId,
                      fixedIp: IPAddress = fipFixedIpAddr) =
        nFloatingIpFromTxt(s"""
            id { $fipId }
            floating_ip_address { $fipIpAddr }
            router_id { $routerId }
            port_id { $portId }
            fixed_ip_address { $fixedIp }
        """)
    protected val boundFip = fip()

    // Main tenant router setup
    protected val externalNetworkId = randomUuidProto
    protected val tntRouterGatewayPortId = randomUuidProto
    protected val tntRouterGatewayPortMac = "11:22:33:fe:dc:ba"
    protected val tntRouterInternalPortId = randomUuidProto
    protected val tntRouterInChainId = inChainId(tntRouterId)
    protected val tntRouterOutChainId =outChainId(tntRouterId)

    protected val mTntRouter = mRouterFromTxt(s"""
        id { $tntRouterId }
        port_ids { $tntRouterInternalPortId }
        port_ids { $tntRouterGatewayPortId }
        inbound_filter_id { $tntRouterInChainId }
        outbound_filter_id { $tntRouterOutChainId }
        """)

    protected val nTntRouterNoGwPort = nRouterFromTxt(s"""
        id { $tntRouterId }
        """)

    protected val nTntRouter = nRouterFromTxt(s"""
        id { $tntRouterId }
        gw_port_id { $tntRouterGatewayPortId }
        """)

    protected val tntRouterGatewayPort = nPortFromTxt(s"""
        id { $tntRouterGatewayPortId }
        network_id { $externalNetworkId }
        mac_address: "$tntRouterGatewayPortMac"
        device_owner: ROUTER_GATEWAY
        """)
    protected val fipArpEntryPath = arpEntryPath(
            externalNetworkId, fipIpAddr.getAddress, tntRouterGatewayPortMac)


    protected val snatRuleId = RouteManager.fipSnatRuleId(fipId)
    protected val dnatRuleId = RouteManager.fipDnatRuleId(fipId)

    protected def snatRule(oChainId: UUID, gatewayPortId: UUID,
                           fixedIpSubnet: IPSubnet = fipFixedIpSubnet) =
        mRuleFromTxt(s"""
            id { $snatRuleId }
            chain_id { $oChainId }
            action: ACCEPT
            out_port_ids { $gatewayPortId }
            nw_src_ip { $fixedIpSubnet }
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
    protected val snat = snatRule(tntRouterOutChainId, tntRouterGatewayPortId)

    protected def dnatRule(iChainId: UUID, gatewayPortId: UUID,
                           fixedIpAddr: IPAddress = fipFixedIpAddr) =
        mRuleFromTxt(s"""
            id { $dnatRuleId }
            chain_id { $iChainId }
            action: ACCEPT
            in_port_ids { $gatewayPortId }
            nw_dst_ip { $fipIpSubnet }
            nat_rule_data {
                nat_targets {
                    nw_start { $fixedIpAddr }
                    nw_end { $fixedIpAddr }
                    tp_start: 1
                    tp_end: 65535
                }
                dnat: true
            }
        """)
    protected val dnat = dnatRule(tntRouterInChainId, tntRouterGatewayPortId)

    protected val inChainDummyRuleIds = """
        rule_ids { msb: 1 lsb: 2 }
        rule_ids { msb: 3 lsb: 4 }
        """
    protected val outChainDummyRuleIds = """
        rule_ids { msb: 5 lsb: 6 }
        rule_ids { msb: 7 lsb: 8 }
        """

    protected val tntRouterInChain = mChainFromTxt(s"""
        id { $tntRouterInChainId }
        $inChainDummyRuleIds
        """)

    protected val tntRouterOutChain = mChainFromTxt(s"""
        id { $tntRouterOutChainId }
        $outChainDummyRuleIds
        """)

    protected val inChainWithDnat = mChainFromTxt(s"""
        id { $tntRouterInChainId }
        rule_ids { $dnatRuleId }
        $inChainDummyRuleIds
        """)

    protected val outChainWithSnat = mChainFromTxt(s"""
        id { $tntRouterOutChainId }
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
        translator = new FloatingIpTranslator(storage, pathBldr)

        bind(tntRouterId, nTntRouter)
        bind(tntRouterInChainId, tntRouterInChain)
        bind(tntRouterOutChainId, tntRouterOutChain)
        bind(tntRouterGatewayPortId, tntRouterGatewayPort)
    }

    "Unassociated floating IP" should "not create anything" in {
        val midoOps = translator.translate(neutron.Create(unboundFip))

        midoOps shouldBe empty
    }

    "Associated floating IP" should "create ARP entry and NAT rules" in {
        val midoOps = translator.translate(neutron.Create(boundFip))

        midoOps should contain inOrderOnly (midonet.CreateNode(fipArpEntryPath),
                                            midonet.Create(snat),
                                            midonet.Create(dnat),
                                            midonet.Update(inChainWithDnat),
                                            midonet.Update(outChainWithSnat))
    }

    "Tenant router for floating IP" should "throw an exception if it doesn't " +
    "have a gateway port assigned" in {
        bind(tntRouterId, nTntRouterNoGwPort)
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
    protected val tntRouter2Id = randomUuidProto
    protected val tntRouter2GwPortId = randomUuidProto
    protected val tntRouter2InChainId = inChainId(tntRouter2Id)
    protected val tntRouter2OutChainId =outChainId(tntRouter2Id)
    protected val fipPort2Id = randomUuidProto
    protected val fixedIp2 = IPAddressUtil.toProto("192.168.1.10")
    protected val fixedIpSubnet2 = IPSubnetUtil.fromAddr(fixedIp2)
    protected val fipMovedRtr2 = fip(tntRouter2Id)
    protected val fipMovedPort2 = fip(portId = fipPort2Id, fixedIp = fixedIp2)
    protected val fipMovedRtr2Port2 =
        fip(routerId = tntRouter2Id, portId = fipPort2Id, fixedIp = fixedIp2)
    protected val snatRtr2 = snatRule(tntRouter2OutChainId, tntRouter2GwPortId)
    protected val dnatRtr2 = dnatRule(tntRouter2InChainId, tntRouter2GwPortId)
    protected val snatPort2 =
        snatRule(tntRouterOutChainId, tntRouterGatewayPortId, fixedIpSubnet2)
    protected val dnatPort2 =
        dnatRule(tntRouterInChainId, tntRouterGatewayPortId, fixedIp2)
    protected val snatRtr2Port2 =
        snatRule(tntRouter2OutChainId, tntRouter2GwPortId, fixedIpSubnet2)
    protected val dnatRtr2Port2 =
        dnatRule(tntRouter2InChainId, tntRouter2GwPortId, fixedIp2)

    protected val nTntRouter2 = nRouterFromTxt(s"""
        id { $tntRouter2Id }
        gw_port_id { $tntRouter2GwPortId }
        """)

    protected val tntRouter2GwPortMac = "77:88:99:ab:cc:ba"
    protected val tntRouter2GwPort = nPortFromTxt(s"""
        id { $tntRouter2GwPortId }
        network_id { $externalNetworkId }
        mac_address: "$tntRouter2GwPortMac"
        device_owner: ROUTER_GATEWAY
        """)

    protected val tntRouter2InChain = mChainFromTxt(s"""
        id { $tntRouter2InChainId }
        $inChainDummyRuleIds
        """)

    protected val tntRouter2OutChain = mChainFromTxt(s"""
        id { $tntRouter2OutChainId }
        $outChainDummyRuleIds
        """)

    protected val tntRouter2InChainWithDnat = mChainFromTxt(s"""
        id { $tntRouter2InChainId }
        rule_ids { $dnatRuleId }
        $inChainDummyRuleIds
        """)

    protected val tntRouter2OutChainWithSnat = mChainFromTxt(s"""
        id { $tntRouter2OutChainId }
        rule_ids { $snatRuleId }
        $outChainDummyRuleIds
        """)

    protected val fipArpEntryPath2 = arpEntryPath(
            externalNetworkId, fipIpAddr.getAddress, tntRouter2GwPortMac)

    before {
        initMockStorage()
        bind(tntRouterInChainId, tntRouterInChain)
        bind(tntRouterOutChainId, tntRouterOutChain)
        bind(tntRouterGatewayPortId, tntRouterGatewayPort)
        bind(tntRouter2InChainId, tntRouter2InChain)
        bind(tntRouter2OutChainId, tntRouter2OutChain)
        bind(tntRouter2GwPortId, tntRouter2GwPort)
        bindAll(Seq(tntRouterId, tntRouter2Id), Seq(nTntRouter, nTntRouter2))
        bindAll(Seq(tntRouterId, tntRouterId), Seq(nTntRouter, nTntRouter))

        translator = new FloatingIpTranslator(storage, pathBldr)
    }

    "FIP UPDATE that keeps the floating IP unbound" should "do nothing" in {
        bind(fipId, unboundFip)
        val midoOps = translator.translate(neutron.Update(unboundFip))

        midoOps shouldBe empty
    }

    "Associating a floating IP to a port" should "add an ARP entry and NAT " +
    "rules" in {
        bind(fipId, unboundFip)
        val midoOps = translator.translate(neutron.Update(boundFip))

        midoOps should contain inOrderOnly (midonet.CreateNode(fipArpEntryPath),
                                            midonet.Create(snat),
                                            midonet.Create(dnat),
                                            midonet.Update(inChainWithDnat),
                                            midonet.Update(outChainWithSnat))
    }

    "Associating a floating IP to a port" should "throw an exception if the " +
    "tenant router doesn't have a gateway port configured" in {
        bind(fipId, unboundFip)
        bind(tntRouterId, nTntRouterNoGwPort)
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

    "Removing a floating IP from a port" should "delete the ARP entry and " +
    "SNAT/DNAT rules, and remove the IDs fo those rules from the inbound / " +
    "outbound chains of the tenant router" in {
        bind(fipId, boundFip)
        val midoOps = translator.translate(neutron.Update(unboundFip))

        midoOps should contain inOrderOnly (midonet.DeleteNode(fipArpEntryPath),
                                            midonet.Delete(classOf[Rule],
                                                           snatRuleId),
                                            midonet.Delete(classOf[Rule],
                                                           dnatRuleId),
                                            midonet.Update(tntRouterInChain),
                                            midonet.Update(tntRouterOutChain))
    }

    "UPDATE that keeps the floating IP on the same port/router " should
    "do nothing" in {
        bind(fipId, boundFip)
        val midoOps = translator.translate(neutron.Update(boundFip))

        midoOps shouldBe empty
    }

    "UpdateOp that moves the floating IP to a different router" should
    "delete the old ARP entry and NAT rules and create new ones on the new " +
    "router" in {
        bind(fipId, boundFip)
        val midoOps = translator.translate(neutron.Update(fipMovedRtr2))

        midoOps should contain inOrderOnly (
                midonet.DeleteNode(fipArpEntryPath),
                midonet.CreateNode(fipArpEntryPath2),
                midonet.Delete(classOf[Rule], snatRuleId),
                midonet.Delete(classOf[Rule], dnatRuleId),
                midonet.Update(tntRouterInChain),
                midonet.Update(tntRouterOutChain),
                midonet.Create(snatRtr2),
                midonet.Create(dnatRtr2),
                midonet.Update(tntRouter2InChainWithDnat),
                midonet.Update(tntRouter2OutChainWithSnat))
    }

    "UpdateOp that moves the floating IP to a different port on the same " +
    "router" should "delete the old NAT rules and create new ones on the " +
    "same router" in {
        bind(fipId, boundFip)
        val midoOps = translator.translate(neutron.Update(fipMovedPort2))

        midoOps should contain inOrderOnly (
                midonet.Delete(classOf[Rule], snatRuleId),
                midonet.Delete(classOf[Rule], dnatRuleId),
                midonet.Update(tntRouterInChain),
                midonet.Update(tntRouterOutChain),
                midonet.Create(snatPort2),
                midonet.Create(dnatPort2),
                midonet.Update(inChainWithDnat),
                midonet.Update(outChainWithSnat))
    }

    "UpdateOp that moves the floating IP to a different port on a different " +
    "router" should "delete the old ARP entry and NAT rules and create new " +
    "ones on the destination router" in {
        bind(fipId, boundFip)
        val midoOps = translator.translate(neutron.Update(fipMovedRtr2Port2))

        midoOps should contain inOrderOnly (
                midonet.DeleteNode(fipArpEntryPath),
                midonet.CreateNode(fipArpEntryPath2),
                midonet.Delete(classOf[Rule], snatRuleId),
                midonet.Delete(classOf[Rule], dnatRuleId),
                midonet.Update(tntRouterInChain),
                midonet.Update(tntRouterOutChain),
                midonet.Create(snatRtr2Port2),
                midonet.Create(dnatRtr2Port2),
                midonet.Update(tntRouter2InChainWithDnat),
                midonet.Update(tntRouter2OutChainWithSnat))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorDeleteTest extends FloatingIpTranslatorTestBase {
    before {
        initMockStorage()
        translator = new FloatingIpTranslator(storage, pathBldr)
    }

    "Deleting an unassociated floating IP" should "not create anything" in {
        bind(fipId, unboundFip)
        val midoOps = translator.translate(neutron.Delete(classOf[FloatingIp],
                                                          fipId))

        midoOps shouldBe empty
    }

    "Deleting an associated floating IP" should "delete the arp entry and " +
    "SNAT/DNAT rules, and remove the IDs fo those rules from the inbound / " +
    "outbound chains of the tenant router" in {
        bind(fipId, boundFip)
        bind(tntRouterId, nTntRouter)
        bind(tntRouterGatewayPortId, tntRouterGatewayPort)
        bind(tntRouterInChainId, inChainWithDnat)
        bind(tntRouterOutChainId, outChainWithSnat)
        val midoOps = translator.translate(neutron.Delete(classOf[FloatingIp],
                                                          fipId))

        midoOps should contain inOrderOnly (midonet.DeleteNode(fipArpEntryPath),
                                            midonet.Delete(classOf[Rule],
                                                           snatRuleId),
                                            midonet.Delete(classOf[Rule],
                                                           dnatRuleId),
                                            midonet.Update(tntRouterInChain),
                                            midonet.Update(tntRouterOutChain))
    }
}