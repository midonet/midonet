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
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.model.Fip64Entry
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.FloatingIp
import org.midonet.cluster.models.Topology.{Port, Router, Rule}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.services.c3po.translators.RouterTranslator.tenantGwPortId
import org.midonet.cluster.util.UUIDUtil.{fromProto, randomUuidProto}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{IPv4Addr, IPv6Addr, MAC}

class FloatingIpTranslatorTestBase extends TranslatorTestBase with ChainManager
                                                              with OpMatchers {
    protected var translator: FloatingIpTranslator = _

    // Floating IP data setup
    protected val fipId = randomUuidProto
    protected val fipIdThatDoesNotExist = randomUuidProto
    protected val tntRouterId = randomUuidProto
    protected val fipPortId = randomUuidProto
    protected val fipIpAddr = IPAddressUtil.toProto("10.10.10.1")
    protected val fipIpSubnet = IPSubnetUtil.fromAddr(fipIpAddr)
    protected val fipFixedIpAddr = IPAddressUtil.toProto("192.168.1.1")
    protected val fipFixedIpSubnet = IPSubnetUtil.fromAddr(fipFixedIpAddr)
    protected val fipIpv6Addr = IPAddressUtil.toProto("2001::1")

    protected val unboundFip = nFloatingIpFromTxt(s"""
        id { $fipId }
        floating_ip_address { $fipIpAddr }
        router_id { $tntRouterId }
        """)

    protected val unboundFip6 = nFloatingIpFromTxt(s"""
        id { $fipId }
        floating_ip_address { $fipIpv6Addr }
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
    protected def fip6(routerId: UUID = tntRouterId,
                       portId: UUID = fipPortId,
                       fixedIp: IPAddress = fipFixedIpAddr) =
        nFloatingIpFromTxt(s"""
            id { $fipId }
            floating_ip_address { $fipIpv6Addr }
            router_id { $routerId }
            port_id { $portId }
            fixed_ip_address { $fixedIp }
        """)

    protected val boundFip = fip()

    // Main tenant router setup
    protected val externalNetworkId = randomUuidProto
    protected val nTntRouterGatewayPortId = randomUuidProto
    protected val mTntRouterGatewayPortId =
        tenantGwPortId(nTntRouterGatewayPortId)
    protected val tntRouterGatewayPortMac = "11:22:33:fe:dc:ba"
    protected val tntRouterInternalPortId = randomUuidProto
    protected val tntRouterInternalPortSubnet =
        IPSubnetUtil.toProto("10.10.11.0/24")
    protected val tntRouterInChainId = inChainId(tntRouterId)
    protected val tntRouterOutChainId = outChainId(tntRouterId)
    protected val tntRouterFloatSnatExactChainId =
        floatSnatExactChainId(tntRouterId)
    protected val tntRouterFloatSnatChainId = floatSnatChainId(tntRouterId)
    protected val tntRouterSkipSnatChainId = skipSnatChainId(tntRouterId)

    protected val mTntRouter = mRouterFromTxt(s"""
        id { $tntRouterId }
        port_ids { $tntRouterInternalPortId }
        port_ids { $nTntRouterGatewayPortId }
        inbound_filter_id { $tntRouterInChainId }
        outbound_filter_id { $tntRouterOutChainId }
        """)

    protected val nTntRouterNoGwPort = nRouterFromTxt(s"""
        id { $tntRouterId }
        """)
    protected val mTntRouterNoGwPort = Router.newBuilder
        .setId(tntRouterId)
        .addPortIds(tntRouterInternalPortId)
        .build()

    protected val nTntRouter = nRouterFromTxt(s"""
        id { $tntRouterId }
        gw_port_id { $nTntRouterGatewayPortId }
        """)

    protected val nTntRouterGatewayPort = nPortFromTxt(s"""
        id { $nTntRouterGatewayPortId }
        network_id { $externalNetworkId }
        mac_address: "$tntRouterGatewayPortMac"
        device_owner: ROUTER_GATEWAY
        """)
    protected val mTntRouterGatwewayPort = Port.newBuilder
        .setId(mTntRouterGatewayPortId)
        .setPortSubnet(IPSubnetUtil.fromAddr(fipIpAddr, 24))
        .build()
    protected val mTntRouterInternalPort = Port.newBuilder
        .setId(tntRouterInternalPortId)
        .setRouterId(tntRouterId)
        .setPortSubnet(tntRouterInternalPortSubnet)
        .build()

    protected val fipArpEntryPath = stateTableStorage.bridgeArpEntryPath(
            externalNetworkId, IPv4Addr(fipIpAddr.getAddress),
            MAC.fromString(tntRouterGatewayPortMac))

    protected def fip64Path(fip: FloatingIp, portId: UUID) =
        stateTableStorage.fip64EntryPath(Fip64Entry(
            IPv4Addr(fip.getFixedIpAddress.getAddress),
            IPv6Addr(fip.getFloatingIpAddress.getAddress),
            portId,
            fip.getRouterId))

    protected val snatExactRuleId = RouteManager.fipSnatExactRuleId(fipId)
    protected val snatRuleId = RouteManager.fipSnatRuleId(fipId)
    protected val skipSnatRuleId = RouteManager.fipSkipSnatRuleId(fipId)
    protected val dnatRuleId = RouteManager.fipDnatRuleId(fipId)
    protected val reverseIcmpDnatRuleId = RouteManager.fipReverseDnatRuleId(fipId)

    protected def snatRule(gatewayPortId: UUID, sourcePortId: UUID,
                           fixedIpSubnet: IPSubnet = fipFixedIpSubnet) =
        mRuleFromTxt(s"""
            id { $snatRuleId }
            type: NAT_RULE
            action: ACCEPT
            condition {
                nw_src_ip { $fixedIpSubnet }
                fragment_policy: ANY
            }
            fip_port_id { $sourcePortId }
            nat_rule_data {
                nat_targets {
                    nw_start { $fipIpAddr }
                    nw_end { $fipIpAddr }
                    tp_start: 0
                    tp_end: 0
                }
                dnat: false
            }
        """)
    protected val snat = snatRule(nTntRouterGatewayPortId, fipPortId)

    protected def snatExactRule(gatewayPortId: UUID, sourcePortId: UUID,
                           fixedIpSubnet: IPSubnet = fipFixedIpSubnet) =
        mRuleFromTxt(s"""
            id { $snatExactRuleId }
            type: NAT_RULE
            action: ACCEPT
            condition {
                out_port_ids { ${tenantGwPortId(gatewayPortId)} }
                nw_src_ip { $fixedIpSubnet }
                fragment_policy: ANY
            }
            fip_port_id { $sourcePortId }
            nat_rule_data {
                nat_targets {
                    nw_start { $fipIpAddr }
                    nw_end { $fipIpAddr }
                    tp_start: 0
                    tp_end: 0
                }
                dnat: false
            }
        """)
    protected val snatExact = snatExactRule(nTntRouterGatewayPortId, fipPortId)

    protected def skipSnatRule(gatewayPortId: UUID, sourcePortId: UUID) =
        mRuleFromTxt(s"""
            id { $skipSnatRuleId }
            type: LITERAL_RULE
            action: ACCEPT
            condition {
                in_port_ids { ${tenantGwPortId(gatewayPortId)} }
                fragment_policy: ANY
            }
            fip_port_id { $sourcePortId }
        """)
    protected val skipSnat = skipSnatRule(nTntRouterGatewayPortId, fipPortId)

    protected def dnatRule(gatewayPortId: UUID, destPortId: UUID,
                           fixedIpAddr: IPAddress = fipFixedIpAddr) =
        mRuleFromTxt(s"""
            id { $dnatRuleId }
            type: NAT_RULE
            action: ACCEPT
            condition {
                nw_dst_ip { $fipIpSubnet }
                fragment_policy: ANY
            }
            fip_port_id { $destPortId }
            nat_rule_data {
                nat_targets {
                    nw_start { $fixedIpAddr }
                    nw_end { $fixedIpAddr }
                    tp_start: 0
                    tp_end: 0
                }
                dnat: true
            }
        """)
    protected val dnat = dnatRule(nTntRouterGatewayPortId, fipPortId)

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

    protected val outChainWithSnatAndReverseIcmpDnat = mChainFromTxt(s"""
        id { $tntRouterOutChainId }
        rule_ids { $reverseIcmpDnatRuleId }
        rule_ids { $snatRuleId }
        $outChainDummyRuleIds
        """)

    protected val tntRouterFloatSnatExactChain = mChainFromTxt(s"""
        id { $tntRouterFloatSnatExactChainId }
        """)

    protected val tntRouterFloatSnatChain = mChainFromTxt(s"""
        id { $tntRouterFloatSnatChainId }
        """)

    protected val tntRouterSkipSnatChain = mChainFromTxt(s"""
        id { $tntRouterSkipSnatChainId }
        """)

    protected val floatSnatExactChainWithFipRule = mChainFromTxt(s"""
        id { $tntRouterFloatSnatExactChainId }
        rule_ids { $snatExactRuleId }
        """)

    protected val floatSnatChainWithFipRuleForGwPort = mChainFromTxt(s"""
        id { $tntRouterFloatSnatChainId }
        rule_ids { $snatRuleId }
        """)
}

/**
 * Tests a Neutron Floating IP Create translation.
 */
@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorCreateTest extends FloatingIpTranslatorTestBase {
    before {
        initMockStorage()
        translator = new FloatingIpTranslator(stateTableStorage)

        bind(tntRouterId, nTntRouter)
        bind(tntRouterId, mTntRouter)
        bind(tntRouterInChainId, tntRouterInChain)
        bind(tntRouterOutChainId, tntRouterOutChain)
        bind(tntRouterFloatSnatExactChainId, tntRouterFloatSnatExactChain)
        bind(tntRouterFloatSnatChainId, tntRouterFloatSnatChain)
        bind(tntRouterSkipSnatChainId, tntRouterSkipSnatChain)
        bind(nTntRouterGatewayPortId, nTntRouterGatewayPort)
        bind(mTntRouterGatewayPortId, mTntRouterGatwewayPort)
        bindAll(Seq(tntRouterInternalPortId), Seq(mTntRouterInternalPort))
    }

    "Unassociated floating IP" should "not create anything" in {
        translator.translate(transaction, Create(unboundFip))

        verifyNoOp(transaction)
    }

    "Associated floating IPv4" should "create ARP entry and NAT rules" in {
        translator.translate(transaction, Create(boundFip))

        verify(transaction).createNode(fipArpEntryPath, null)
        verify(transaction).create(snat)
        verify(transaction).create(snatExact)
        verify(transaction).create(dnat)
        verify(transaction).update(inChainWithDnat, null)
        verify(transaction).update(floatSnatExactChainWithFipRule, null)
        verify(transaction).update(floatSnatChainWithFipRuleForGwPort, null)
    }

    "Unassociated IPv6 FIP" should "do nothing" in {
        translator.translate(transaction, Create(unboundFip6))

        verifyNoOp(transaction)
    }

    "Associated floating IPv6" should "add an entry to the FIP64 table" in {
        val entry = fip6()
        translator.translate(transaction, Create(entry))

        val path = fip64Path(entry, mTntRouterGatewayPortId)
        verify(transaction).createNode(path, null)
    }

    "Tenant router for floating IP" should "throw an exception if it doesn't " +
    "have a gateway port assigned" in {
        bind(tntRouterId, nTntRouterNoGwPort)
        bind(tntRouterId, mTntRouterNoGwPort)
        val te = intercept[TranslationException] {
            translator.translate(transaction, Create(boundFip))
        }
        te.getCause should not be null
        te.getCause match {
            case ise: IllegalStateException if ise.getMessage != null =>
                ise.getMessage should startWith (
                    s"Router ${UUIDUtil.fromProto(tntRouterId)} has no port")
            case e => fail("Expected an IllegalStateException.", e)
        }
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorUpdateTest extends FloatingIpTranslatorTestBase {
    protected val tntRouter2Id = randomUuidProto
    protected val nTntRouter2GwPortId = randomUuidProto
    protected val mTntRouter2GwPortId = tenantGwPortId(nTntRouter2GwPortId)
    protected val tntRouter2InChainId = inChainId(tntRouter2Id)
    protected val tntRouter2OutChainId = outChainId(tntRouter2Id)
    protected val tntRouter2FloatSnatExactChainId =
        floatSnatExactChainId(tntRouter2Id)
    protected val tntRouter2FloatSnatChainId = floatSnatChainId(tntRouter2Id)
    protected val tntRouter2SkipSnatChainId = skipSnatChainId(tntRouter2Id)
    protected val fipPort2Id = randomUuidProto
    protected val fixedIp2 = IPAddressUtil.toProto("192.168.1.10")
    protected val fixedIpSubnet2 = IPSubnetUtil.fromAddr(fixedIp2)
    protected val fipMovedRtr2 = fip(tntRouter2Id)
    protected val fipMovedPort2 = fip(portId = fipPort2Id, fixedIp = fixedIp2)
    protected val fipMovedRtr2Port2 =
        fip(routerId = tntRouter2Id, portId = fipPort2Id, fixedIp = fixedIp2)

    protected val snatExactRtr2 =
        snatExactRule(nTntRouter2GwPortId, fipPortId)
    protected val snatRtr2 =
        snatRule(nTntRouter2GwPortId, fipPortId)
    protected val dnatRtr2 =
        dnatRule(nTntRouter2GwPortId, fipPortId)

    protected val snatExactPort2 =
        snatExactRule(nTntRouterGatewayPortId, fipPort2Id, fixedIpSubnet2)
    protected val snatPort2 =
        snatRule(nTntRouterGatewayPortId, fipPort2Id, fixedIpSubnet2)
    protected val dnatPort2 =
        dnatRule(nTntRouterGatewayPortId, fipPort2Id, fixedIp2)

    protected val snatExactRtr2Port2 =
        snatExactRule(nTntRouter2GwPortId, fipPort2Id, fixedIpSubnet2)
    protected val snatRtr2Port2 =
        snatRule(nTntRouter2GwPortId, fipPort2Id, fixedIpSubnet2)
    protected val dnatRtr2Port2 =
        dnatRule(nTntRouter2GwPortId, fipPort2Id, fixedIp2)

    protected val nTntRouter2 = nRouterFromTxt(s"""
        id { $tntRouter2Id }
        gw_port_id { $nTntRouter2GwPortId }
        """)

    protected val tntRouter2GwPortMac = "77:88:99:ab:cc:ba"
    protected val nTntRouter2GwPort = nPortFromTxt(s"""
        id { $nTntRouter2GwPortId }
        network_id { $externalNetworkId }
        mac_address: "$tntRouter2GwPortMac"
        device_owner: ROUTER_GATEWAY
        """)
    protected val mTntRouter2GwPort = Port.newBuilder
        .setId(mTntRouter2GwPortId)
        .setPortSubnet(fipIpSubnet)
        .build()

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

    protected val tntRouter2OutChainWithSnatAndReverseIcmpDnat = mChainFromTxt(s"""
        id { $tntRouter2OutChainId }
        rule_ids { $reverseIcmpDnatRuleId }
        rule_ids { $snatRuleId }
        $outChainDummyRuleIds
        """)

    protected val tntRouter2FloatSnatExactChain = mChainFromTxt(s"""
        id { $tntRouter2FloatSnatExactChainId }
        """)

    protected val tntRouter2FloatSnatChain = mChainFromTxt(s"""
        id { $tntRouter2FloatSnatChainId }
        """)

    protected val tntRouter2SkipSnatChain = mChainFromTxt(s"""
        id { $tntRouter2SkipSnatChainId }
        """)

    protected val tntRouter2FloatSnatExactChainWithFipRule = mChainFromTxt(s"""
        id { $tntRouter2FloatSnatExactChainId }
        rule_ids { $snatExactRuleId }
        """)

    protected val tntRouter2FloatSnatChainWithFipRuleForGwPort = mChainFromTxt(s"""
        id { $tntRouter2FloatSnatChainId }
        rule_ids { $snatRuleId }
        """)

    protected val fipArpEntryPath2 = stateTableStorage.bridgeArpEntryPath(
            externalNetworkId, IPv4Addr(fipIpAddr.getAddress),
            MAC.fromString(tntRouter2GwPortMac))

    before {
        initMockStorage()
        bind(tntRouterInChainId, tntRouterInChain)
        bind(tntRouterOutChainId, tntRouterOutChain)
        bind(tntRouterFloatSnatExactChainId, tntRouterFloatSnatExactChain)
        bind(tntRouterFloatSnatChainId, tntRouterFloatSnatChain)
        bind(tntRouterSkipSnatChainId, tntRouterSkipSnatChain)
        bind(nTntRouterGatewayPortId, nTntRouterGatewayPort)
        bind(mTntRouterGatewayPortId, mTntRouterGatwewayPort)
        bind(tntRouter2InChainId, tntRouter2InChain)
        bind(tntRouter2OutChainId, tntRouter2OutChain)
        bind(tntRouter2FloatSnatExactChainId, tntRouter2FloatSnatExactChain)
        bind(tntRouter2FloatSnatChainId, tntRouter2FloatSnatChain)
        bind(tntRouter2SkipSnatChainId, tntRouter2SkipSnatChain)
        bind(nTntRouter2GwPortId, nTntRouter2GwPort)
        bind(mTntRouter2GwPortId, mTntRouter2GwPort)
        bindAll(Seq(tntRouterId, tntRouter2Id), Seq(nTntRouter, nTntRouter2))
        bindAll(Seq(tntRouterId, tntRouterId), Seq(nTntRouter, nTntRouter))
        bindAll(Seq(tntRouterInternalPortId), Seq(mTntRouterInternalPort))

        translator = new FloatingIpTranslator(stateTableStorage)
    }

    "FIP UPDATE that keeps the floating IP unbound" should "do nothing" in {
        bind(fipId, unboundFip)
        translator.translate(transaction, Update(unboundFip))

        verifyNoOp(transaction)
    }

    "Associating a floating IP to a port" should "add an ARP entry and NAT " +
    "rules" in {
        bind(fipId, unboundFip)
        translator.translate(transaction, Update(boundFip))

        verify(transaction).createNode(fipArpEntryPath, null)
        verify(transaction).create(snat)
        verify(transaction).create(snatExact)
        verify(transaction).create(dnat)
        verify(transaction).update(inChainWithDnat, null)
        verify(transaction).update(floatSnatExactChainWithFipRule, null)
        verify(transaction).update(floatSnatChainWithFipRuleForGwPort, null)
    }

    "Associating a floating IP to a port" should "throw an exception if the " +
    "tenant router doesn't have a gateway port configured" in {
        bind(fipId, unboundFip)
        bind(tntRouterId, nTntRouterNoGwPort)
        bind(tntRouterId, mTntRouterNoGwPort)
        val te = intercept[TranslationException] {
            translator.translate(transaction, Update(boundFip))
        }

        te.getCause should not be null
        te.getCause match {
            case ise: IllegalStateException if ise.getMessage != null =>
                ise.getMessage should startWith (
                    s"Router ${UUIDUtil.fromProto(tntRouterId)} has no port")
            case e => fail("Expected an IllegalStateException.", e)
        }
    }

    "Removing a floating IP from a port" should "delete the ARP entry and " +
    "SNAT/DNAT rules, and remove the IDs fo those rules from the inbound / " +
    "outbound chains of the tenant router" in {
        bind(fipId, boundFip)
        translator.translate(transaction, Update(unboundFip))

        verify(transaction).deleteNode(fipArpEntryPath)
        verify(transaction).delete(classOf[Rule], snatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], dnatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], reverseIcmpDnatRuleId,
                                   ignoresNeo = true)
    }

    "UPDATE that keeps the floating IP on the same port/router " should
    "do nothing" in {
        bind(fipId, boundFip)
        translator.translate(transaction, Update(boundFip))

        verifyNoOp(transaction)
    }

    "UpdateOp that moves the floating IP to a different router" should
    "delete the old ARP entry and NAT rules and create new ones on the new " +
    "router" in {
        bind(fipId, boundFip)
        translator.translate(transaction, Update(fipMovedRtr2))

        verify(transaction).deleteNode(fipArpEntryPath)
        verify(transaction).createNode(fipArpEntryPath2, null)
        verify(transaction).delete(classOf[Rule], snatExactRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], snatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], dnatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], reverseIcmpDnatRuleId,
                                   ignoresNeo = true)
        verify(transaction).create(snatExactRtr2)
        verify(transaction).create(snatRtr2)
        verify(transaction).create(dnatRtr2)
        verify(transaction).update(tntRouter2InChainWithDnat, null)
        verify(transaction).update(tntRouter2FloatSnatExactChainWithFipRule, null)
        verify(transaction).update(tntRouter2FloatSnatChainWithFipRuleForGwPort, null)
    }

    "UpdateOp that moves the floating IP to a different port on the same " +
    "router" should "delete the old NAT rules and create new ones on the " +
    "same router" in {
        bind(fipId, boundFip)
        translator.translate(transaction, Update(fipMovedPort2))

        verify(transaction).delete(classOf[Rule], snatExactRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], snatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], dnatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], reverseIcmpDnatRuleId,
                                   ignoresNeo = true)
        verify(transaction).create(snatExactPort2)
        verify(transaction).create(snatPort2)
        verify(transaction).create(dnatPort2)
        verify(transaction).update(inChainWithDnat, null)
        verify(transaction).update(floatSnatExactChainWithFipRule, null)
        verify(transaction).update(floatSnatChainWithFipRuleForGwPort, null)
    }

    "UpdateOp that moves the floating IP to a different port on a different " +
    "router" should "delete the old ARP entry and NAT rules and create new " +
    "ones on the destination router" in {
        bind(fipId, boundFip)
        bind(snatExactRuleId, snatExact)
        bind(snatRuleId, snat)
        bind(skipSnatRuleId, skipSnat)
        translator.translate(transaction, Update(fipMovedRtr2Port2))

        verify(transaction).deleteNode(fipArpEntryPath)
        verify(transaction).createNode(fipArpEntryPath2, null)
        verify(transaction).delete(classOf[Rule], snatExactRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], snatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], dnatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], reverseIcmpDnatRuleId,
                                   ignoresNeo = true)
        verify(transaction).create(snatExactRtr2Port2)
        verify(transaction).create(snatRtr2Port2)
        verify(transaction).create(dnatRtr2Port2)
        verify(transaction).update(tntRouter2InChainWithDnat, null)
        verify(transaction).update(tntRouter2FloatSnatExactChainWithFipRule, null)
        verify(transaction).update(tntRouter2FloatSnatChainWithFipRuleForGwPort, null)
    }

    "Associating unbound floating IPv6" should "add the entry to the FIP64 table" in {
        bind(fipId, unboundFip6)
        translator.translate(transaction, Update(fip6()))

        verify(transaction).createNode(fip64Path(fip6(), mTntRouterGatewayPortId),
                                       null)
    }

    "Re-associating a floating IPv6" should "remove the old entry and create " +
                                            "a new one" in {
        val oldFip = fip6()
        val newFip = fip6(portId = randomUuidProto)
        bind(fipId, oldFip)
        translator.translate(transaction, Update(newFip))

        verify(transaction).deleteNode(fip64Path(oldFip, mTntRouterGatewayPortId),
                                       idempotent = true)
        verify(transaction).createNode(fip64Path(newFip, mTntRouterGatewayPortId),
                                       null)
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorDeleteTest extends FloatingIpTranslatorTestBase {
    before {
        initMockStorage()
        translator = new FloatingIpTranslator(stateTableStorage)

        bind(fipIdThatDoesNotExist, null, classOf[FloatingIp])
    }

    "Deleting an unassociated floating IP" should "not create anything" in {
        bind(fipId, unboundFip)
        translator.translate(transaction, Delete(classOf[FloatingIp], fipId))

        verifyNoOp(transaction)
    }

    "Deleting an associated floating IP" should "delete the arp entry and " +
    "SNAT/DNAT rules, and remove the IDs fo those rules from the inbound / " +
    "outbound chains of the tenant router" in {
        bind(fipId, boundFip)
        bind(tntRouterId, nTntRouter)
        bind(nTntRouterGatewayPortId, nTntRouterGatewayPort)
        bind(mTntRouterGatewayPortId, mTntRouterGatwewayPort)
        bind(tntRouterInChainId, inChainWithDnat)
        bind(tntRouterOutChainId, outChainWithSnatAndReverseIcmpDnat)
        translator.translate(transaction, Delete(classOf[FloatingIp], fipId))

        verify(transaction).deleteNode(fipArpEntryPath)
        verify(transaction).delete(classOf[Rule], snatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], dnatRuleId, ignoresNeo = true)
        verify(transaction).delete(classOf[Rule], reverseIcmpDnatRuleId,
                                   ignoresNeo = true)
    }

    "Deleting a non-existent FIP" should "not raise an error" in {
        translator.translate(transaction, Delete(classOf[FloatingIp],
                                                 fipIdThatDoesNotExist))
        verifyNoOp(transaction)
    }

    "Deleting an associated IPv6 FIP" should "remove the entry from the FIP64 table" in {
        val fip = fip6()
        bind(tntRouterId, nTntRouter)
        bind(fip.getId, fip)
        translator.translate(transaction, Delete(classOf[FloatingIp],
                                                 fip.getId))

        verify(transaction).deleteNode(fip64Path(fip, mTntRouterGatewayPortId),
                                       idempotent = true)
    }

    "Deleting an unassociated floating IPv6" should "not create anything" in {
        bind(tntRouterId, nTntRouter)
        bind(fipId, unboundFip6)
        translator.translate(transaction, Delete(classOf[FloatingIp], fipId))

        verifyNoOp(transaction)
    }
}
