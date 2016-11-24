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

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.protobuf.Message
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.mockito.Mockito.when
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.{MatchResult, Matcher}

import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronBgpSpeaker, NeutronPort}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{CreateNode, DeleteNode, Operation, Create => CreateOp, Delete => DeleteOp, Update => UpdateOp}
import org.midonet.cluster.services.c3po.OpType
import org.midonet.cluster.services.c3po.translators.L2GatewayConnectionTranslator.l2gwNetworkPortId
import org.midonet.cluster.services.c3po.translators.RouterInterfaceTranslator.sameSubnetSnatRuleId
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.SequenceDispenser.{OverlayTunnelKey, SequenceType}
import org.midonet.cluster.util.UUIDUtil.{fromProto, randomUuidProto}
import org.midonet.cluster.util._
import org.midonet.packets.{ARP, IPv4Addr, MAC}

trait OpMatchers {
    /**
     * This attempts to match the expected Message against the actual one,
     * ignoring the "id" field when the expected message doesn't have the field
     * set. This is needed because often an ID of a model is assigned randomly.
     */
    class ContainsOp[M <: Message](expected: Operation[M])
    extends Matcher[OperationList] {
        private def matchesModuloId(expected: Message,
                                    actual: Message): Boolean = {
            if (actual.getClass != expected.getClass) return false

            val expectedFields = expected.getAllFields.asScala.toMap
            var actualFields = actual.getAllFields.asScala.toMap
            val idFld = expected.getDescriptorForType.findFieldByName("id")
            if (idFld != null && !expected.hasField(idFld)) {
                // Use the ID field only when the expectation specifies it.
                actualFields = actualFields.filterKeys(_.getName != "id")
            }
            expectedFields == actualFields
        }

        private def matchesOp[E <: Message, A <: Message]
        (expected: Operation[E], actual: Operation[A]) = {
            if (expected.opType == actual.opType) {
                expected match {
                    case CreateOp(m) =>
                        matchesModuloId(m, actual.asInstanceOf[CreateOp[A]].model)
                    case UpdateOp(m, _) =>
                        matchesModuloId(m, actual.asInstanceOf[UpdateOp[A]].model)
                    case DeleteOp(clazz, id) => expected == actual
                }
            } else false
        }

        def apply(left: OperationList) =
            MatchResult(left.exists { matchesOp(expected, _) },
                        s"$left\n\ndoes not contain a matching operation\n\n" +
                        s"$expected",
                        s"$left\n\ncontains a matching operation\n\n$expected")
    }

    def containOp[M <: Message](expected: Operation[M]) =
        new ContainsOp[M](expected)
}

/* A common base class for testing NeutronPort CRUD translation. */
class PortTranslatorTest extends TranslatorTestBase with ChainManager
                                                    with OpMatchers {

    protected var translator: PortTranslator = _
    protected val portId = randomUuidProto
    protected val portIdThatDoesNotExist = randomUuidProto
    protected val portJUuid = UUIDUtil.fromProto(portId)
    protected val networkId = randomUuidProto
    protected val tenantId = "neutron tenant"
    protected val mac = "00:11:22:33:44:55"

    protected val portWithPeerId = randomUuidProto
    protected val peerRouterPortId = randomUuidProto
    protected val portForVipId = randomUuidProto

    // This below is so the translators can generate overlay tunnel keys
    protected val backendCfg = new MidonetBackendConfig(
        ConfigFactory.parseString(""" zookeeper.root_key = '/' """))
    protected val seqDispenser = new SequenceDispenser(null, backendCfg) {
        private val mockCounter = new AtomicInteger(0)
        def reset(): Unit = mockCounter.set(0)
        override def next(which: SequenceType): Future[Int] = {
            Future.successful(mockCounter.incrementAndGet())
        }

        override def current(which: SequenceType): Future[Int] = {
            Future.successful(mockCounter.get())
        }
    }

    /** Use this method to retrieve the current tunnel key from the sequencer,
      * it's useful to verify the result of a port creation.
      */
    def currTunnelKey = seqDispenser.current(OverlayTunnelKey).value.get.get

    protected def portBase(portId: UUID = portId,
                           adminStateUp: Boolean = false) = s"""
        id { $portId }
        network_id { $networkId }
        tenant_id: 'neutron tenant'
        mac_address: '$mac'
        admin_state_up: ${if (adminStateUp) "true" else "false"}
        """

    protected val portBaseDown = portBase(adminStateUp = false)
    protected val portBaseUp = portBase(adminStateUp = true)

    protected val vifPortUp = nPortFromTxt(portBaseUp)

    val midoNetworkBase = s"""
        id { $networkId }
        """
    val midoNetwork = mNetworkFromTxt(midoNetworkBase)

    private val midoPortBase = s"""
        id { $portId }
        network_id { $networkId }
        tunnel_key: 1
        """
    val midoPortBaseUp = mPortFromTxt(midoPortBase + """
        admin_state_up: true
        """)
    val midoPortBaseDown = mPortFromTxt(midoPortBase + """
        admin_state_up: false
        """)

    val inboundChainId = inChainId(portId)
    val outboundChainId = outChainId(portId)
    val spoofChainId = antiSpoofChainId(portId)
    val mPortWithChains = mPortFromTxt(s"""
        $midoPortBaseUp
        inbound_filter_id { $inboundChainId }
        outbound_filter_id { $outboundChainId }
        """)

    val mPortDownWithChains = mPortFromTxt(s"""
        $midoPortBaseDown
        inbound_filter_id { $inboundChainId }
        outbound_filter_id { $outboundChainId }
        """)

    val ipv4Subnet1Addr = "127.0.0.0"
    val ipv4Subnet1Cidr = s"$ipv4Subnet1Addr/8"
    val ipv4Subnet1 = IPSubnetUtil.toProto(ipv4Subnet1Cidr)
    val ipv4Addr1Txt = "127.0.0.1"
    val ipv4Addr1 = IPAddressUtil.toProto(ipv4Addr1Txt)

    val ipv6Subnet1Cidr = "fe80:0:0:0:0:202:b3ff:8329/64"
    val ipv6Subnet1 = IPSubnetUtil.toProto(ipv6Subnet1Cidr)
    val ipv6Addr1Txt = "fe80:0:0:0:0:202:b3ff:8329"
    val ipv6Addr1 = IPAddressUtil.toProto(ipv6Addr1Txt)

    val nIpv4Subnet1Id = randomUuidProto
    val nIpv4Subnet1 = nSubnetFromTxt(s"""
        id { $nIpv4Subnet1Id }
        network_id { $networkId }
        cidr { $ipv4Subnet1 }
        ip_version: 4
        """)

    val nIpv6Subnet1Id = randomUuidProto
    val nIpv6Subnet1= nSubnetFromTxt(s"""
        id { $nIpv6Subnet1Id }
        network_id { $networkId }
        cidr { $ipv6Subnet1 }
        ip_version: 6
        """)

    val nNetworkBase = nNetworkFromTxt(s"""
        id { $networkId }
         """)

    val midoIpv4Dhcp = s"""
            id { $nIpv4Subnet1Id }
            network_id { $networkId }
            subnet_address { $ipv4Subnet1 }
            router_if_port_id { $peerRouterPortId }
        """
    val mIpv4Dhcp = mDhcpFromTxt(midoIpv4Dhcp)

    val midoIpv6Dhcp = s"""
            id { $nIpv6Subnet1Id }
            network_id { $networkId }
            subnet_address { $ipv6Subnet1 }
        """
    val mIpv6Dhcp = mDhcpFromTxt(midoIpv6Dhcp)

    val midoNetworkWithSubnets = s"""
            $midoNetworkBase
            dhcp_ids { $nIpv4Subnet1Id }
            dhcp_ids { $nIpv6Subnet1Id }
        """
    val mNetworkWithSubnets = mNetworkFromTxt(midoNetworkWithSubnets)

    val midoNetworkWithIpv4Subnet = s"""
            $midoNetworkBase
            dhcp_ids { $nIpv4Subnet1Id }
        """
    val mNetworkWithIpv4Subnet = mNetworkFromTxt(midoNetworkWithIpv4Subnet)

}

/* Contains generic port translation tests */
@RunWith(classOf[JUnitRunner])
class PortTranslationTest extends PortTranslatorTest {

    before {
        initMockStorage()

        translator = new PortTranslator(stateTableStorage, seqDispenser)
        bind(portIdThatDoesNotExist, null, classOf[NeutronPort])
    }

    "Deleting a non-existent port" should "not raise an error" in {
        translator.translate(transaction,
                             DeleteOp(classOf[NeutronPort], portIdThatDoesNotExist))

        verifyNoOp(transaction)
    }
}

/* Contains common constructs for testing VIF port CRUD translation. */
class VifPortTranslationTest extends PortTranslatorTest {
    val sgId1 = randomUuidProto
    val sgId2 = randomUuidProto
    val fipId1 = randomUuidProto
    val fipId2 = randomUuidProto
    val floatingIp1Addr = "118.67.101.185"
    val floatingIp2Addr = "118.67.201.25"
    val tntRouterId = randomUuidProto
    val gwPortId = RouterTranslator.tenantGwPortId(tntRouterId)
    val gwPortMac = "e0:05:2d:fd:16:0b"
    val gwPortMacUpdated = "e0:05:2d:fd:16:0c"
    val externalNetworkId = randomUuidProto

    val vifPortWithFixedIps = nPortFromTxt(s"""
        $portBaseUp
        fixed_ips {
          ip_address {
            version: V4
            address: '$ipv4Addr1Txt'
          }
          subnet_id { $nIpv4Subnet1Id }
        }
        fixed_ips {
          ip_address {
            version: V6
            address: '$ipv6Addr1Txt'
          }
          subnet_id { $nIpv6Subnet1Id }
        }
        """)
    protected val vifArpEntryPath =
        stateTableStorage.bridgeArpEntryPath(networkId, IPv4Addr(ipv4Addr1Txt),
                                             MAC.fromString(mac))
    protected val vifMacEntryPath =
        stateTableStorage.bridgeMacEntryPath(networkId, 0, MAC.fromString(mac),
                                             portId)

    val vifPortWithFipsAndSgs = nPortFromTxt(s"""
        $vifPortWithFixedIps
        security_groups { $sgId1 }
        security_groups { $sgId2 }
        """)

    val vifPortWithFloatingIpIds = nPortFromTxt(s"""
        $vifPortWithFixedIps
        floating_ip_ids { $fipId1 }
        floating_ip_ids { $fipId2 }
        """)

    val ipAddrGroup1InChainId = randomUuidProto
    val ipAddrGroup1OutChainId = randomUuidProto
    val ipAddrGroup1 = mIPAddrGroupFromTxt(s"""
        id { $sgId1 }
        inbound_chain_id { $ipAddrGroup1InChainId }
        outbound_chain_id { $ipAddrGroup1OutChainId }
        """)
    val ipAddrGroup2InChainId = randomUuidProto
    val ipAddrGroup2OutChainId = randomUuidProto
    val ipAddrGroup2 = mIPAddrGroupFromTxt(s"""
        id { $sgId2 }
        inbound_chain_id { $ipAddrGroup2InChainId }
        outbound_chain_id { $ipAddrGroup2OutChainId }
        """)

    val mIpv4DhcpWithHostAdded = mDhcpFromTxt(s"""
          $mIpv4Dhcp
          hosts {
            mac: "$mac"
            ip_address { $ipv4Addr1 }
          }
        """)

    val mIpv6DhcpWithHostAdded = mDhcpFromTxt(s"""
          $mIpv6Dhcp
          hosts {
            mac: "$mac"
            ip_address { $ipv6Addr1 }
          }
        """)

    val inChainRule1 = randomUuidProto
    val inChainRule2 = randomUuidProto
    val inChainRule3 = randomUuidProto
    val inboundChain = mChainFromTxt(s"""
        id { $inboundChainId }
        rule_ids { $inChainRule1 }
        rule_ids { $inChainRule2 }
        rule_ids { $inChainRule3 }
        """)
    val outChainRule1 = randomUuidProto
    val outboundChain = mChainFromTxt(s"""
        id { $outboundChainId }
        rule_ids { $outChainRule1 }
        """)
    val antiSpoofChainRule = randomUuidProto
    val antiSpoofChain = mChainFromTxt(s"""
        id { $spoofChainId }
        rule_ids { $antiSpoofChainRule }
        """)

    val floatingIp1 = nFloatingIpFromTxt(s"""
        id { $fipId1 }
        router_id { $tntRouterId }
        port_id { $portId }
        floating_ip_address {
          version: V4
          address: '$floatingIp1Addr'
        }
        fixed_ip_address {
          version: V4
          address: '$ipv4Addr1Txt'
        }
        """)
    val floatingIp2 = nFloatingIpFromTxt(s"""
        id { $fipId2 }
        router_id { $tntRouterId }
        port_id { $portId }
        floating_ip_address {
          version: V4
          address: '$floatingIp2Addr'
        }
        fixed_ip_address {
          version: V4
          address: '$ipv4Addr1Txt'
        }
        """)
    val nTntRouter = nRouterFromTxt(s"""
        id { $tntRouterId }
        gw_port_id { $gwPortId }
        """)
    val nGwPortUpdatedMac = nPortFromTxt(s"""
        id { $gwPortId }
        network_id { $externalNetworkId }
        mac_address: "$gwPortMacUpdated"
        device_owner: ROUTER_GATEWAY
        """)
    val nGwPort = nPortFromTxt(s"""
        id { $gwPortId }
        network_id { $externalNetworkId }
        mac_address: "$gwPortMac"
        device_owner: ROUTER_GATEWAY
        """)
    val fip1SnatRuleId = RouteManager.fipSnatRuleId(fipId1)
    val fip1SnatRule = mRuleFromTxt(s"""
        id { $fip1SnatRuleId }
        nat_rule_data {
            dnat: false
        }
        """)
    val fip2SnatRuleId = RouteManager.fipSnatRuleId(fipId2)
    val fip2SnatRule = mRuleFromTxt(s"""
        id { $fip2SnatRuleId }
        nat_rule_data {
            dnat: false
        }
        """)
    val fip1DnatRuleId = RouteManager.fipDnatRuleId(fipId1)
    val fip1DnatRule = mRuleFromTxt(s"""
        id { $fip1DnatRuleId }
        nat_rule_data {
            dnat: true
        }
        """)
    val fip2DnatRuleId = RouteManager.fipDnatRuleId(fipId2)
    val fip2DnatRule = mRuleFromTxt(s"""
        id { $fip2DnatRuleId }
        nat_rule_data {
            dnat: true
        }
        """)
    val mGwPort = mPortFromTxt(s"""
        id { $gwPortId }
        network_id { $externalNetworkId }
        fip_nat_rule_ids { $fip1SnatRuleId }
        fip_nat_rule_ids { $fip1DnatRuleId }
        fip_nat_rule_ids { $fip2SnatRuleId }
        fip_nat_rule_ids { $fip2DnatRuleId }
        """)
    val fip1ArpEntryPath =
        stateTableStorage.bridgeArpEntryPath(externalNetworkId,
                                             IPv4Addr(floatingIp1Addr),
                                             MAC.fromString(gwPortMac))
    val fip2ArpEntryPath =
        stateTableStorage.bridgeArpEntryPath(externalNetworkId,
                                             IPv4Addr(floatingIp2Addr),
                                             MAC.fromString(gwPortMac))
    val fip1ArpEntryPathMacUpdated =
        stateTableStorage.bridgeArpEntryPath(externalNetworkId,
                                             IPv4Addr(floatingIp1Addr),
                                             MAC.fromString(gwPortMacUpdated))
    val fip2ArpEntryPathMacUpdated =
        stateTableStorage.bridgeArpEntryPath(externalNetworkId,
                                             IPv4Addr(floatingIp2Addr),
                                             MAC.fromString(gwPortMacUpdated))
}

/**
 * Tests the Neutron VIF Port model Create translation.
 */
@RunWith(classOf[JUnitRunner])
class VifPortCreateTranslationTest extends VifPortTranslationTest {

    before {

        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(nIpv4Subnet1Id, nIpv4Subnet1)
        bind(nIpv6Subnet1Id, nIpv6Subnet1)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bind(nIpv6Subnet1Id, mIpv6Dhcp)
        bind(sgId1, ipAddrGroup1)
        bind(sgId2, ipAddrGroup2)
        bindAll(Seq(sgId1, sgId2), Seq(ipAddrGroup1, ipAddrGroup2))
        bindAll(Seq(sgId1), Seq(ipAddrGroup1))
        bindAll(Seq(sgId2), Seq(ipAddrGroup2))
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    "Anti Spoof Chain" should "exist on a port with allowed addr pairs" in {

        val vifPortWithAllowedAddressPairs = nPortFromTxt(s"""
          $portBaseUp
          fixed_ips {
            ip_address {
              version: V4
              address: '6.6.6.6'
            }
            subnet_id { $nIpv4Subnet1Id }
          }
          allowed_address_pairs {
            ip_address {
              version: V4
              address: '1.2.1.2'
              prefix_length: 32
            }
            mac_address: "01:02:03:0a:0b:0c"
          }
          allowed_address_pairs {
            ip_address {
              version: V4
              address: '2.3.2.3'
              prefix_length: 32
            }
            mac_address: "0a:0b:0b:0a:0b:0c"
          }
          """)

        val jumpRule = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_chain_id { $spoofChainId }
            }
            """)

        val fixedIp = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: RETURN
            chain_id: { $spoofChainId }
            condition {
                dl_src: "$mac"
                nw_src_ip {
                    version: V4
                    address: "6.6.6.6"
                    prefix_length: 32
                }
                fragment_policy: ANY
            }
            """)

        val addrPairOne = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: RETURN
            chain_id: { $spoofChainId }
            condition {
                dl_src: "0a:0b:0b:0a:0b:0c"
                nw_src_ip {
                    version: V4
                    address: "2.3.2.3"
                    prefix_length: 32
                }
                fragment_policy: ANY
            }
            """)

        val addrPairTwo = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: RETURN
            chain_id: { $spoofChainId }
            condition {
                dl_src: "01:02:03:0a:0b:0c"
                nw_src_ip {
                    version: V4
                    address: '1.2.1.2'
                    prefix_length: 32
                }
                fragment_policy: ANY
            }
            """)

        val arpRuleForFixedIp = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: RETURN
            condition {
                dl_type: ${ARP.ETHERTYPE}
                nw_src_ip {
                    version: V4
                    address: "6.6.6.6"
                    prefix_length: 32
                }
                fragment_policy: ANY
            }
            """)

        val arpRuleOne = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: RETURN
            condition {
                dl_type: ${ARP.ETHERTYPE}
                nw_src_ip {
                    version: V4
                    address: "2.3.2.3"
                    prefix_length: 32
                }
                fragment_policy: ANY
            }
            """)

        val arpRuleTwo = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: RETURN
            condition {
                dl_type: ${ARP.ETHERTYPE}
                nw_src_ip {
                    version: V4
                    address: '1.2.1.2'
                    prefix_length: 32
                }
                fragment_policy: ANY
            }
            """)

        val dropAll = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: DROP
            condition {
                fragment_policy: ANY
            }
            """)

        val dhcpRule = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: RETURN
            condition {
                tp_src: {
                    start: 68
                    end: 68
                }
                tp_dst: {
                    start: 67
                    end: 67
                }
                fragment_policy: ANY
            }
            """)

        translator.translate(transaction, CreateOp(vifPortWithAllowedAddressPairs))

        // For the outbound chain
        midoOps should containOp[Message] (CreateOp(jumpRule))

        // For the Anti Spoof Chain
        midoOps should containOp[Message] (CreateOp(dhcpRule))
        midoOps should containOp[Message] (CreateOp(arpRuleForFixedIp))
        midoOps should containOp[Message] (CreateOp(fixedIp))
        midoOps should containOp[Message] (CreateOp(arpRuleOne))
        midoOps should containOp[Message] (CreateOp(addrPairOne))
        midoOps should containOp[Message] (CreateOp(arpRuleTwo))
        midoOps should containOp[Message] (CreateOp(addrPairTwo))
        midoOps should containOp[Message] (CreateOp(dropAll))
    }

    "Fixed IPs for a new VIF port" should "add hosts to DHCPs" in {
        translator.translate(transaction, CreateOp(vifPortWithFixedIps))

        midoOps should contain (UpdateOp(mIpv4DhcpWithHostAdded))
        midoOps should contain (UpdateOp(mIpv6DhcpWithHostAdded))
    }

    "A created VIF port with port_security_enabled=false" should "not have " +
    "any rules on its inbound, outbound, and antispoof chain" in {

        val vifPortNoPortSec = nPortFromTxt(s"""
          $portBaseUp
          fixed_ips {
            ip_address {
              version: V4
              address: '6.6.6.6'
            }
            subnet_id { $nIpv4Subnet1Id }
          }
          port_security_enabled: false
          """)

        translator.translate(transaction, CreateOp(vifPortNoPortSec))

        val inChain = findChainOp(midoOps, OpType.Create, inboundChainId)
        inChain should not be null
        inChain.getName shouldBe s"OS_PORT_${portJUuid}_INBOUND"
        inChain.getRuleIdsList.size shouldBe 0

        val outChain= findChainOp(midoOps, OpType.Create, outboundChainId)
        outChain should not be null
        outChain.getName shouldBe s"OS_PORT_${portJUuid}_OUTBOUND"
        outChain.getRuleIdsList.size shouldBe 0

        val antiSpoofChain = findChainOp(midoOps, OpType.Create, spoofChainId)
        antiSpoofChain should not be null
        antiSpoofChain.getName shouldBe s"OS_PORT_${portJUuid}_ANTI_SPOOF"
        antiSpoofChain.getRuleIdsList.size shouldBe 0
    }

    "A created VIF port" should "have security bindings" in {

        seqDispenser.reset()
        translator.translate(transaction, CreateOp(vifPortWithFipsAndSgs))

        midoOps should contain (CreateOp(mPortWithChains))

        // Security bindings.
        val revFlowRuleOutbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            condition {
                match_return_flow: true
            }
            chain_id { $outboundChainId }
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            condition {
                match_return_flow: true
            }
            chain_id { $inboundChainId }
            """)

        val antiSpoofJumpRule = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_chain_id { $spoofChainId }
            }
            """)

        val jumpRuleIn1 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $inboundChainId }
            jump_rule_data {
              jump_chain_id { $ipAddrGroup1InChainId }
            }
            """)

        val jumpRuleIn2 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $inboundChainId }
            jump_rule_data {
              jump_chain_id { $ipAddrGroup2InChainId }
            }
            """)

        val jumpRuleOut1 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $outboundChainId }
            jump_rule_data {
              jump_chain_id { $ipAddrGroup1OutChainId }
            }
            """)

        val jumpRuleOut2 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $outboundChainId }
            jump_rule_data {
              jump_chain_id { $ipAddrGroup2OutChainId }
            }
            """)

        val dropNonArpIn = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $inboundChainId }
            action: DROP
            condition {
                dl_type: ${ARP.ETHERTYPE}
                inv_dl_type: true
                fragment_policy: ANY
            }
            """)

        val dropNonArpOut = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $outboundChainId }
            action: DROP
            condition {
                dl_type: ${ARP.ETHERTYPE}
                inv_dl_type: true
                fragment_policy: ANY
            }
            """)

        val inChain = findChainOp(midoOps, OpType.Create, inboundChainId)
        inChain should not be null
        inChain.getName shouldBe s"OS_PORT_${portJUuid}_INBOUND"
        inChain.getRuleIdsList.size shouldBe 0

        val outChain= findChainOp(midoOps, OpType.Create, outboundChainId)
        outChain should not be null
        outChain.getName shouldBe s"OS_PORT_${portJUuid}_OUTBOUND"
        outChain.getRuleIdsList.size shouldBe 0

        val antiSpoofChain = findChainOp(midoOps, OpType.Create, spoofChainId)
        antiSpoofChain should not be null
        antiSpoofChain.getName shouldBe s"OS_PORT_${portJUuid}_ANTI_SPOOF"
        antiSpoofChain.getRuleIdsList.size shouldBe 0

        midoOps should containOp[Message] (CreateOp(revFlowRuleOutbound))
        midoOps should containOp[Message] (CreateOp(revFlowRuleInbound))
        midoOps should containOp[Message] (CreateOp(antiSpoofJumpRule))
        midoOps should containOp[Message] (CreateOp(jumpRuleIn1))
        midoOps should containOp[Message] (CreateOp(jumpRuleIn2))
        midoOps should containOp[Message] (CreateOp(jumpRuleOut1))
        midoOps should containOp[Message] (CreateOp(jumpRuleOut2))
        midoOps should containOp[Message] (CreateOp(dropNonArpIn))
        midoOps should containOp[Message] (CreateOp(dropNonArpOut))
        midoOps should contain (CreateNode(vifMacEntryPath))
        midoOps should contain (CreateNode(vifArpEntryPath))

        // IP Address Groups.
        val ipAddrGrp1 = mIPAddrGroupFromTxt(s"""
            id { $sgId1 }
            ip_addr_ports {
                ip_address { $ipv4Addr1 }
                port_ids { $portId }
            }
            ip_addr_ports {
                ip_address { $ipv6Addr1 }
                port_ids { $portId }
            }
            inbound_chain_id { $ipAddrGroup1InChainId }
            outbound_chain_id { $ipAddrGroup1OutChainId }
            """)
        val ipAddrGrp2 = mIPAddrGroupFromTxt(s"""
            id { $sgId2 }
            ip_addr_ports {
                ip_address { $ipv4Addr1 }
                port_ids { $portId }
            }
            ip_addr_ports {
                ip_address { $ipv6Addr1 }
                port_ids { $portId }
            }
            inbound_chain_id { $ipAddrGroup2InChainId }
            outbound_chain_id { $ipAddrGroup2OutChainId }
            """)
        midoOps should contain (UpdateOp(ipAddrGrp1))
        midoOps should contain (UpdateOp(ipAddrGrp2))
    }

    // TODO test that VIF port CREATE creates an external network route if the
    //      port is attached to an external network.
    // TODO test that VIF/DHCP/interface/router GW port CREATE creates a tunnel
    //      key, and makes the tunnel key reference the port.
}

/**
 * Tests the Neutron port specific to the port binding fields
 */
@RunWith(classOf[JUnitRunner])
class VifPortBindingTranslationTest extends VifPortTranslationTest {

    private val hostId = randomUuidProto
    private val hostName = "hostname"
    private val interfaceName = "tap0"
    private val vifPortWithBinding = nPortFromTxt(s"""
        $portBaseUp
        host_id: '$hostName'
        profile {
            interface_name: '$interfaceName'
        }
        """)

    private val mPortWithBinding = mPortFromTxt(s"""
        $mPortWithChains
        host_id { $hostId }
        interface_name: '$interfaceName'
        """)

    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(inboundChainId, inboundChain)
        bind(outboundChainId, outboundChain)
        bind(spoofChainId, antiSpoofChain)

        bind(portId, mPortWithBinding)
        bind(portId, vifPortWithBinding)
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    "VIF port UPDATE with no change " should "NOT update binding" in {
        translator.translate(transaction, UpdateOp(vifPortWithBinding))
        midoOps.exists {
            case UpdateOp(obj: Port, _) => true
            case _ => false
        } shouldBe false
    }
}

/**
 * Tests the Neutron VIF Port model Update / Delete.
 */
@RunWith(classOf[JUnitRunner])
class VifPortUpdateDeleteTranslationTest extends VifPortTranslationTest {
    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(nIpv4Subnet1Id, nIpv4Subnet1)
        bind(nIpv6Subnet1Id, nIpv6Subnet1)
        bind(nIpv4Subnet1Id, mIpv4DhcpWithHostAdded)
        bind(nIpv6Subnet1Id, mIpv6DhcpWithHostAdded)
        bind(portId, mPortWithChains)
        bind(portId, vifPortWithFixedIps)
        bind(sgId1, ipAddrGroup1)
        bind(sgId2, ipAddrGroup2)
        bind(inboundChainId, inboundChain)
        bind(outboundChainId, outboundChain)
        bind(spoofChainId, antiSpoofChain)
        bindAll(Seq(sgId1, sgId2), Seq(ipAddrGroup1, ipAddrGroup2))
        bindAll(Seq(sgId1), Seq(ipAddrGroup1))
        bindAll(Seq(sgId2), Seq(ipAddrGroup2))
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    "VIF port UPDATE" should "update port admin state" in {

        val vifPortDown = nPortFromTxt(s"$portBaseDown")
        translator.translate(transaction, UpdateOp(vifPortDown))
        midoOps should contain (UpdateOp(mPortDownWithChains))
    }

    val updatedFixedIpTxt = "127.0.0.2"
    val updatedFixedIp = IPAddressUtil.toProto(updatedFixedIpTxt)
    val vifPortWithFixedIps2 = nPortFromTxt(s"""
        $portBaseUp
        fixed_ips {
          ip_address {
            version: V4
            address: '$updatedFixedIpTxt'
          }
          subnet_id { $nIpv4Subnet1Id }
        }
        """)

    "UPDATE VIF port with fixed IPs" should "delete old DHCP host entries " +
    "and create new DHCP host entries" in {
        translator.translate(transaction, UpdateOp(vifPortWithFixedIps2))
        val mIpv4DhcpWithHostUpdated = mDhcpFromTxt(s"""
              $mIpv4Dhcp
              hosts {
                mac: "$mac"
                ip_address { $updatedFixedIp }
              }
            """)
        midoOps should contain (UpdateOp(mIpv4DhcpWithHostUpdated))
    }

    val vifPortWithFipsAndSgs2 = nPortFromTxt(s"""
        $vifPortWithFixedIps2
        security_groups { $sgId1 }
        security_groups { $sgId2 }
        """)

    "UPDATE VIF port with fixed IPs" should "update security rules" in {
        bind(portId, vifPortWithFipsAndSgs)

        translator.translate(transaction, UpdateOp(vifPortWithFipsAndSgs2))

        val revFlowRuleOutbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            condition {
                match_return_flow: true
            }
            chain_id { $outboundChainId }
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            condition {
                match_return_flow: true
            }
            chain_id { $inboundChainId }
            """)

        val jumpRuleIn1 = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_chain_id { $ipAddrGroup1InChainId }
            }
            """)

        val jumpRuleIn2 = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_chain_id { $ipAddrGroup2InChainId }
            }
            """)

        val jumpRuleOut1 = mRuleFromTxt(s"""
            chain_id { $outboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_chain_id { $ipAddrGroup1OutChainId }
            }
            """)

        val jumpRuleOut2 = mRuleFromTxt(s"""
            chain_id { $outboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_chain_id { $ipAddrGroup2OutChainId }
            }
            """)

        val dropNonArpIn = mRuleFromTxt(s"""
            action: DROP
            type: LITERAL_RULE
            chain_id { $inboundChainId }
            condition {
                dl_type: ${ARP.ETHERTYPE}
                inv_dl_type: true
                fragment_policy: ANY
            }
            """)

        val dropNonArpOut = mRuleFromTxt(s"""
            action: DROP
            type: LITERAL_RULE
            chain_id { $outboundChainId }
            condition {
                dl_type: ${ARP.ETHERTYPE}
                inv_dl_type: true
                fragment_policy: ANY
            }
            """)

        midoOps should contain (DeleteOp(classOf[Rule], inChainRule1))
        midoOps should contain (DeleteOp(classOf[Rule], inChainRule2))
        midoOps should contain (DeleteOp(classOf[Rule], inChainRule3))
        midoOps should contain (DeleteOp(classOf[Rule], outChainRule1))
        midoOps should contain (DeleteOp(classOf[Rule], antiSpoofChainRule))
        midoOps should containOp[Message] (CreateOp(revFlowRuleOutbound))
        midoOps should containOp[Message] (CreateOp(revFlowRuleInbound))
        midoOps should containOp[Message] (CreateOp(jumpRuleIn1))
        midoOps should containOp[Message] (CreateOp(jumpRuleIn2))
        midoOps should containOp[Message] (CreateOp(jumpRuleOut1))
        midoOps should containOp[Message] (CreateOp(jumpRuleOut2))
        midoOps should containOp[Message] (CreateOp(dropNonArpIn))
        midoOps should containOp[Message] (CreateOp(dropNonArpOut))

        val ipAddrGrp1 = mIPAddrGroupFromTxt(s"""
            id { $sgId1 }
            ip_addr_ports {
                ip_address { $updatedFixedIp }
                port_ids { $portId }
            }
            inbound_chain_id { $ipAddrGroup1InChainId }
            outbound_chain_id { $ipAddrGroup1OutChainId }
            """)
        val ipAddrGrp2 = mIPAddrGroupFromTxt(s"""
            id { $sgId2 }
            ip_addr_ports {
                ip_address { $updatedFixedIp }
                port_ids { $portId }
            }
            inbound_chain_id { $ipAddrGroup2InChainId }
            outbound_chain_id { $ipAddrGroup2OutChainId }
            """)
        midoOps should contain (UpdateOp(ipAddrGrp1))
        midoOps should contain (UpdateOp(ipAddrGrp2))
    }

    "DELETE VIF port with fixed IPs" should "delete the MidoNet Port" in {
        when(storage.getAll(classOf[IPAddrGroup], Seq()))
            .thenReturn(Future.successful(Seq()))
        translator.translate(transaction,  DeleteOp(classOf[NeutronPort], portId))
        midoOps should contain (DeleteOp(classOf[Port], portId))
        midoOps should contain (DeleteNode(vifMacEntryPath))
        midoOps should contain (DeleteNode(vifArpEntryPath))
    }

    "DELETE VIF port with fixed IPs" should "delete DHCP host entries and " +
    "chains." in {
        val ipAddrGrp1 = mIPAddrGroupFromTxt(s"""
            id { $sgId1 }
            inbound_chain_id { $ipAddrGroup1InChainId }
            outbound_chain_id { $ipAddrGroup1OutChainId }
            """)
        val ipAddrGrp2 = mIPAddrGroupFromTxt(s"""
            id { $sgId2 }
            inbound_chain_id { $ipAddrGroup2InChainId }
            outbound_chain_id { $ipAddrGroup2OutChainId }
            """)

        bind(portId, vifPortWithFipsAndSgs)
        when(transaction.getAll(classOf[IPAddrGroup], Seq(sgId1, sgId2)))
            .thenReturn(Seq(ipAddrGrp1, ipAddrGrp2))

        translator.translate(transaction, DeleteOp(classOf[NeutronPort], portId))

        midoOps should contain only(
            DeleteNode(vifMacEntryPath),
            DeleteNode(vifArpEntryPath),
            DeleteOp(classOf[Port], portId),
            UpdateOp(mIpv4Dhcp),
            UpdateOp(mIpv6Dhcp),
            DeleteOp(classOf[Chain], inboundChainId),
            DeleteOp(classOf[Chain], outboundChainId),
            DeleteOp(classOf[Chain], spoofChainId),
            UpdateOp(ipAddrGrp1),
            UpdateOp(ipAddrGrp2))
    }

    "DELETE VIF port with floating IPs attached" should "delete the ARP " +
    "table entries" in {
        bind(portId, vifPortWithFloatingIpIds)
        bind(fipId1, floatingIp1)
        bind(fipId2, floatingIp2)
        bind(tntRouterId, nTntRouter)
        bind(gwPortId, nGwPort)
        when(storage.getAll(classOf[IPAddrGroup], Seq()))
            .thenReturn(Future.successful(Seq()))

        translator.translate(transaction, DeleteOp(classOf[NeutronPort], portId))
        midoOps should contain (DeleteNode(fip1ArpEntryPath))
        midoOps should contain (DeleteNode(fip2ArpEntryPath))
    }

    "UPDATE MAC address of a VIF port with floating IPs attached" should
    "delete the old ARP table entries related to old MAC and " +
    "create the new ARP table entries related to new MAC" in {
        bind(tntRouterId, nTntRouter)
        bind(portId, vifPortWithFloatingIpIds)
        bind(gwPortId, nGwPort)
        bind(gwPortId, mGwPort)
        /*
           This test assumes that FIPs and its rules are fetched with getAll()
           as this seems the more efficient way.

           To support valid but less efficient implementations, fetching them
           one-by-one use:

             bind(fip1SnatRuleId, fip1SnatRule)
             bind(fip1DnatRuleId, fip1DnatRule)
             bind(fipId1, floatingIp1)
             ...

           The order or the rules is not fixed, so all permutations are bound to
           to assure test robustness.
         */
        val natRuleIds = Seq(fip1SnatRuleId, fip1DnatRuleId,
                             fip2SnatRuleId, fip2DnatRuleId)
        val natRules = Seq(fip1SnatRule, fip1DnatRule,
                           fip2SnatRule, fip2DnatRule)
        bindAllInAnyOrder(natRuleIds, natRules, classOf[Rule])

        val fipIds = Seq(fipId1, fipId2)
        val fips = Seq(floatingIp1, floatingIp2)
        bindAllInAnyOrder(fipIds, fips, classOf[FloatingIp])

        translator.translate(transaction, UpdateOp(nGwPortUpdatedMac))
        midoOps should contain (DeleteNode(fip1ArpEntryPath))
        midoOps should contain (CreateNode(fip1ArpEntryPathMacUpdated))
        midoOps should contain (DeleteNode(fip2ArpEntryPath))
        midoOps should contain (CreateNode(fip2ArpEntryPathMacUpdated))
    }
}

class DhcpPortTranslationTest extends PortTranslatorTest {
    protected val gwIpv4AddrTxt = "127.0.0.20"
    protected val gwIpv4Addr = IPAddressUtil.toProto(gwIpv4AddrTxt)
    protected val nIpv4Subnet1WithGwIP = nSubnetFromTxt(s"""
        $nIpv4Subnet1
        gateway_ip { $gwIpv4Addr }
        """)
    protected val dhcpPort = nPortFromTxt(portBaseUp + s"""
        device_owner: DHCP
        fixed_ips {
          ip_address {
            version: V4
            address: '$ipv4Addr1Txt'
          }
          subnet_id { $nIpv4Subnet1Id }
        }
        fixed_ips {
          ip_address {
            version: V6
            address: '$ipv6Addr1Txt'
          }
          subnet_id { $nIpv6Subnet1Id }
        }
        """)

    protected val mPortWithRPortPeer = mPortFromTxt(s"""
        id { $portWithPeerId }
        network_id { $networkId }
        admin_state_up: true
        peer_id { $peerRouterPortId }
        """)

    protected val routerPortAddrTxt = "127.0.0.100"
    protected val routerPortAddr = IPAddressUtil.toProto(routerPortAddrTxt)
    protected val routerId = randomUuidProto
    protected val mRouterPort = mPortFromTxt(s"""
        id { $peerRouterPortId }
        router_id { $routerId }
        peer_id { $portWithPeerId }
        port_address { $gwIpv4Addr }
        """)

    protected val mRouterWithGwPort = mRouterFromTxt(s"""
        id { $routerId }
        """)

    protected val mNetworkWithDhcpPort = mNetworkFromTxt(s"""
        $midoNetworkWithSubnets
        port_ids { $portWithPeerId }
        """)

    protected val mIpv4DhcpWithDhcpConfigured = mDhcpFromTxt(s"""
          $mIpv4Dhcp
          server_address { $ipv4Addr1 }
          opt121_routes {
            dst_subnet {
              version: V4
              address: "169.254.169.254"
              prefix_length: 32
            }
            gateway { $ipv4Addr1 }
          }
        """)
}

@RunWith(classOf[JUnitRunner])
class DhcpPortCreateTranslationTest extends DhcpPortTranslationTest {
    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(networkId, mNetworkWithDhcpPort)
        bind(nIpv4Subnet1Id, nIpv4Subnet1WithGwIP)
        bind(nIpv6Subnet1Id, nIpv6Subnet1)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bind(nIpv6Subnet1Id, mIpv6Dhcp)
        bind(portWithPeerId, mPortWithRPortPeer)
        bind(peerRouterPortId, mRouterPort)
        bind(routerId, mRouterWithGwPort)
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    "DHCP port CREATE" should "configure DHCP" in {
        translator.translate(transaction, CreateOp(dhcpPort))

        midoOps.size shouldBe 4
        midoOps.head shouldBe CreateOp(mRouteFromTxt(s"""
            id { ${RouteManager.metadataServiceRouteId(peerRouterPortId)} }
            src_subnet { $ipv4Subnet1 }
            dst_subnet { ${IPSubnetUtil.toProto("169.254.169.254/32")} }
            next_hop: PORT
            next_hop_port_id { $peerRouterPortId }
            next_hop_gateway { $ipv4Addr1 }
            weight: 100
            """))

        // Order of DHCP updates is undefined.
        midoOps(1) shouldBe a [UpdateOp[_]]
        midoOps(2) shouldBe a [UpdateOp[_]]
        midoOps should contain(UpdateOp(mIpv4DhcpWithDhcpConfigured))

        midoOps(3) shouldBe CreateOp(midoPortBaseUp)
    }
}

@RunWith(classOf[JUnitRunner])
class DhcpPortUpdateDeleteTranslationTest extends DhcpPortTranslationTest {
    protected val routeId = randomUuidProto
    protected val mRoute = mRouteFromTxt(s"""
        id { $routeId }
        src_subnet {
            version: V4
            address: "10.10.10.0"
            prefix_length: 24
        }
        dst_subnet { ${IPSubnetUtil.toProto("169.254.169.254/32")} }
        next_hop: PORT
        next_hop_port_id { $peerRouterPortId }
        next_hop_gateway { ${IPAddressUtil.toProto("10.10.10.1")} }
        weight: 100
        """)

    protected val mdsRouteId = randomUuidProto
    protected val mMDSRoute = mRouteFromTxt(s"""
        id { $mdsRouteId }
        src_subnet { $ipv4Subnet1 }
        dst_subnet { ${IPSubnetUtil.toProto("169.254.169.254/32")} }
        next_hop: PORT
        next_hop_port_id { $peerRouterPortId }
        next_hop_gateway { $ipv4Addr1 }
        weight: 100
        """)

    protected val mRouterWithRoute = mRouterFromTxt(s"""
        id { $routerId }
        """)
    protected val mRouterWithMDSRoute = mRouterFromTxt(s"""
        $mRouterWithRoute
        """)

    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(portId, midoPortBaseUp)
        bind(portId, dhcpPort)
        bind(networkId, mNetworkWithDhcpPort)
        bind(nIpv4Subnet1Id, nIpv4Subnet1WithGwIP)
        bind(nIpv6Subnet1Id, nIpv6Subnet1)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bind(nIpv6Subnet1Id, mIpv6Dhcp)
        bind(portWithPeerId, mPortWithRPortPeer)
        bind(peerRouterPortId, mRouterPort)
        bind(routerId, mRouterWithMDSRoute)
        bind(routeId, mRoute)
        bind(mdsRouteId, mMDSRoute)
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    "DHCP port UPDATE" should "update port admin state" in {
        val dhcpPortDown = dhcpPort.toBuilder.setAdminStateUp(false).build
        translator.translate(transaction, UpdateOp(dhcpPortDown))

        midoOps should contain(UpdateOp(midoPortBaseDown))
    }

    // TODO Add an assert that the fixed IPs haven't been changed.

    "DHCP port  DELETE" should "delete the MidoNet Port" in {
        when(transaction.getAll(classOf[Route], List.empty)).thenReturn(List.empty)
        translator.translate(transaction, DeleteOp(classOf[NeutronPort], portId))
        midoOps should contain only(
            DeleteOp(classOf[Port], portId),
            UpdateOp(mIpv4Dhcp),
            UpdateOp(mIpv6Dhcp))
    }

    it should "ignore an already deleted subnet referenced by a fixed IP" in {
        // The subnet has already been deleted.
        bind(nIpv4Subnet1Id, null, classOf[Dhcp])
        translator.translate(transaction, DeleteOp(classOf[NeutronPort], portId))
        midoOps should contain only(
            DeleteOp(classOf[Port], portId),
            UpdateOp(mIpv6Dhcp))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpPortTranslationTest extends PortTranslatorTest {
    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(portId, null, classOf[Port])
        bind(networkId, midoNetwork)
    }

    protected val fipPortUp = nPortFromTxt(portBaseUp + """
        device_owner: FLOATINGIP
        """)

    "Floating IP port CREATE" should "not create a Network port" in {
        translator.translate(transaction, CreateOp(fipPortUp))
        midoOps shouldBe empty
    }

    "Floating IP port UPDATE" should "NOT update Port" in {
        translator.translate(transaction, UpdateOp(fipPortUp))
        midoOps shouldBe empty
     }

    "Floating IP DELETE" should "NOT delete the MidoNet Port" in {
        bind(portId, fipPortUp)
        translator.translate(transaction, DeleteOp(classOf[NeutronPort], portId))
        midoOps shouldBe empty
    }
}

@RunWith(classOf[JUnitRunner])
class VipPortTranslationTest extends VifPortTranslationTest {
    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(networkId, midoNetwork)
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    protected val vipPortUp = nPortFromTxt(portBaseUp + """
        device_owner: LOADBALANCER
        """)
    protected val vipPortDown = nPortFromTxt(portBaseDown + """
        device_owner: LOADBALANCER
        """)
    protected val vipPortNewIp = nPortFromTxt(s"""
        $portBaseUp
        fixed_ips {
          ip_address {
            version: V4
            address: '$ipv4Addr1Txt'
          }
          subnet_id { $nIpv4Subnet1Id }
        }
        device_owner: LOADBALANCER
        """)

    "VIP port CREATE" should "create a MidoNet port" in {
        translator.translate(transaction, CreateOp(vipPortUp))
        midoOps should contain(CreateOp(mPortWithChains))
    }

    "VIP port UPDATE" should "update port admin state" in {
        bind(portId, mPortDownWithChains)
        bind(portId, vipPortUp)
        bind(inboundChainId, inboundChain)
        bind(outboundChainId, outboundChain)
        bind(spoofChainId, antiSpoofChain)

        translator.translate(transaction, UpdateOp(vipPortDown))
        midoOps should contain(UpdateOp(mPortDownWithChains))
    }

    "VIP port DELETE" should "delete the MidoNet Port" in {
        bind(portId, vipPortUp)
        translator.translate(transaction, DeleteOp(classOf[NeutronPort], portId))
        midoOps should contain(DeleteOp(classOf[Port], portId))
    }

    "VIP port UPDATE of IP" should "throw an error" in {
        bind(portId, vipPortUp)
        translator.translate(transaction, UpdateOp(vipPortNewIp))
    }
}

class RouterInterfacePortTranslationTest extends PortTranslatorTest {
    import PortManager._

    protected val deviceId = java.util.UUID.randomUUID
    protected val routerId = UUIDUtil.toProto(deviceId)
    protected val peerPortId = routerInterfacePortPeerId(portWithPeerId)
    protected val routerIfPortBase = portBase(portId = portWithPeerId,
                                              adminStateUp = true)
    protected val routerIfPortIpStr = "127.0.0.2"
    protected val routerIfPortIp = IPAddressUtil.toProto(routerIfPortIpStr)
    protected val routerIfPortIp2Str = "127.0.0.3"
    protected val routerIfPortIp2 = IPAddressUtil.toProto(routerIfPortIp2Str)
    protected val routerIfPortMac = "01:01:01:02:02:02"
    protected val routerIfPort = nPortFromTxt(routerIfPortBase + s"""
        fixed_ips {
            ip_address { $routerIfPortIp }
            subnet_id { $nIpv4Subnet1Id }
        }
        mac_address: "$routerIfPortMac"
        device_owner: ROUTER_INTERFACE
        device_id: "$deviceId"
        """)
    protected val routerIfPortWith2Ips = nPortFromTxt(routerIfPortBase + s"""
        fixed_ips {
            ip_address { $routerIfPortIp }
            subnet_id { $nIpv4Subnet1Id }
        }
        fixed_ips {
            ip_address { $routerIfPortIp2 }
            subnet_id { $nIpv4Subnet1Id }
        }
        mac_address: "$routerIfPortMac"
        device_owner: ROUTER_INTERFACE
        device_id: "$deviceId"
        """)
    protected val preRouteChainId = inChainId(peerPortId)
    protected val postRouteChainId = outChainId(peerPortId)
    protected val mTenantRouter = mRouterFromTxt(s"""
        id { $routerId }
        inbound_filter_id { $preRouteChainId }
        outbound_filter_id { $postRouteChainId }
        """)
    protected val mRouterPortWithPeer = mPortFromTxt(s"""
        id { $peerPortId }
        admin_state_up: true
        peer_id { $portWithPeerId }
        router_id: { $routerId }
        """)
    protected val snatRuleId = sameSubnetSnatRuleId(outChainId(routerId),
                                                    peerPortId)
    protected val snatRule = mRuleFromTxt(s"""
        id: { $snatRuleId }
        type: NAT_RULE
        chain_id: { $postRouteChainId }
        action: ACCEPT
        condition {
            match_forward_flow: true
            in_port_ids: { $portWithPeerId }
            out_port_ids: { $portWithPeerId }
            nw_dst_ip {
                version: V4
                address: "169.254.169.254"
                prefix_length: 32
            }
            nw_dst_inv: true
        }
        nat_rule_data {
            nat_targets {
              nw_start {
                  version: V4
                  address: "$routerIfPortIpStr"
              }
              nw_end {
                  version: V4
                  address: "$routerIfPortIpStr"
              }
              tp_start: 1024
              tp_end: 65535
            }
            dnat: false
        }
    """)
    protected val revSnatRuleId = sameSubnetSnatRuleId(inChainId(routerId),
                                                       peerPortId)
    protected val revSnatRule = mRuleFromTxt(s"""
        id : { $revSnatRuleId }
        type: NAT_RULE
        chain_id: { $preRouteChainId }
        action: ACCEPT
        condition {
            match_return_flow: true
            in_port_ids: { $portWithPeerId }
            nw_dst_ip {
                version: V4
                address: "$routerIfPortIpStr"
                prefix_length: 32
            }
        }
        nat_rule_data {
            dnat: false
            reverse: true
        }
    """)

    protected def getBridgePortBaseWithPeer() = mPortFromTxt(s"""
        id { $portWithPeerId }
        network_id { $networkId }
        tunnel_key: $currTunnelKey
        admin_state_up: true
        """)
}

@RunWith(classOf[JUnitRunner])
class RouterInterfacePortCreateTranslationTest
        extends RouterInterfacePortTranslationTest {
    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    "Router interface port CREATE" should "create a normal Network port" in {
        bind(networkId, nNetworkBase)
        bind(networkId, mNetworkWithIpv4Subnet)
        when(transaction.getAll(classOf[NeutronBgpSpeaker])).thenReturn(Seq())
        translator.translate(transaction, CreateOp(routerIfPort))
        midoOps should contain only (
            CreateNode(stateTableStorage.bridgeArpEntryPath(
                networkId, IPv4Addr(routerIfPortIpStr),
                MAC.fromString(routerIfPortMac))),
            CreateNode(stateTableStorage.bridgeMacEntryPath(
                networkId, 0, MAC.fromString(routerIfPortMac),
                portWithPeerId)),
            CreateOp(getBridgePortBaseWithPeer))
    }

    "Router interface port with several IPs CREATE" should
    "create a port with corresponding ARP entries" in {
        bind(networkId, nNetworkBase)
        bind(networkId, mNetworkWithIpv4Subnet)
        when(transaction.getAll(classOf[NeutronBgpSpeaker])).thenReturn(Seq())
        translator.translate(transaction, CreateOp(routerIfPortWith2Ips))
        midoOps should contain only (
          CreateNode(stateTableStorage.bridgeArpEntryPath(
              networkId, IPv4Addr(routerIfPortIpStr),
              MAC.fromString(routerIfPortMac))),
          CreateNode(stateTableStorage.bridgeArpEntryPath(
              networkId, IPv4Addr(routerIfPortIp2Str),
              MAC.fromString(routerIfPortMac))),
          CreateNode(stateTableStorage.bridgeMacEntryPath(
              networkId, 0, MAC.fromString(routerIfPortMac),
              portWithPeerId)),
          CreateOp(getBridgePortBaseWithPeer))
    }
}

@RunWith(classOf[JUnitRunner])
class RouterInterfacePortUpdateDeleteTranslationTest
        extends RouterInterfacePortTranslationTest {
    import RouterInterfaceTranslator._

    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bind(portWithPeerId, midoPortBaseUp)
        bind(portWithPeerId, routerIfPort)
        bind(networkId, midoNetwork)
        bind(peerPortId, mRouterPortWithPeer)
        bind(routerId, mTenantRouter)
        bindAll(Seq(snatRuleId, revSnatRuleId),
                Seq(snatRule, revSnatRule))
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    "Router interface port UPDATE" should "update Network Port, Local" +
    ", Next Hop Routes and SNAT rules" in {
        bind(portId, routerIfPort)
        bind(nIpv4Subnet1Id, nIpv4Subnet1)

        // Generate a new port with updated Fixed IP address.
        // This port is used for the update operation.
        val newIp = "127.0.0.200"
        val newIfPortIp = IPAddressUtil.toProto(newIp)
        val routerIfPortUpdated = nPortFromTxt(routerIfPortBase + s"""
            fixed_ips {
                ip_address { $newIfPortIp }
                subnet_id { $nIpv4Subnet1Id }
            }
            mac_address: "$routerIfPortMac"
            device_owner: ROUTER_INTERFACE
            device_id: "$deviceId"
        """)

        // Update port
        translator.translate(transaction, UpdateOp(routerIfPortUpdated))

        val mPort = mPortFromTxt(s"""
            id: { $peerPortId }
            router_id: { $routerId }
            peer_id: { $portWithPeerId }
            admin_state_up: true
            port_address: { $newIfPortIp }
            """)
        midoOps should contain (UpdateOp(mPort))

        val rifRouteId = RouteManager.routerInterfaceRouteId(
            peerPortId)
        val rifRoute = mRouteFromTxt(s"""
            id: { $rifRouteId }
            src_subnet {
              version: V4
              address: "0.0.0.0"
              prefix_length: 0
            }
            dst_subnet {
              version: V4
              address: "$ipv4Subnet1Addr"
              prefix_length: 8
            }
            next_hop: PORT
            next_hop_port_id: { $peerPortId }
            weight: 100
            """)
        midoOps should contain (UpdateOp(rifRoute))

        val localRouteId = RouteManager.localRouteId(peerPortId)
        val localRoute = mRouteFromTxt(s"""
            id: { $localRouteId }
            src_subnet {
              version: V4
              address: "0.0.0.0"
              prefix_length: 0
            }
            dst_subnet {
              version: V4
              address: "$newIp"
              prefix_length: 32
            }
            next_hop: LOCAL
            next_hop_port_id: { $peerPortId }
            weight: 100
            """)
        midoOps should contain (UpdateOp(localRoute))

        val snatRuleBldr = snatRule.toBuilder
        snatRuleBldr.getNatRuleDataBuilder.getNatTargetsBuilder(0)
            .setNwStart(newIfPortIp).setNwEnd(newIfPortIp)
        midoOps should contain (UpdateOp(snatRuleBldr.build))

        val revSnatRuleBldr = revSnatRule.toBuilder
        revSnatRuleBldr.getConditionBuilder
            .setNwDstIp(IPSubnetUtil.fromAddress(newIfPortIp))
        midoOps should contain (UpdateOp(revSnatRuleBldr.build))
     }

    "Router interface port DELETE" should "delete both MidoNet Router " +
    "Interface Network Port, its peer Router Port and SNAT rules" in {
        bind(portId, routerIfPort)
        translator.translate(transaction,
                             DeleteOp(classOf[NeutronPort], portWithPeerId))
        midoOps should contain only (
                DeleteOp(classOf[Port], portWithPeerId),
                DeleteOp(classOf[Port], peerPortId),
                DeleteOp(classOf[Rule],
                               sameSubnetSnatRuleId(preRouteChainId,
                                                    peerPortId)),
                DeleteOp(classOf[Rule],
                               sameSubnetSnatRuleId(postRouteChainId,
                                                    peerPortId)),
                DeleteNode(stateTableStorage.bridgeArpEntryPath(
                    networkId, IPv4Addr(routerIfPortIpStr),
                    MAC.fromString(routerIfPortMac))),
                DeleteNode(stateTableStorage.bridgeMacEntryPath(
                    networkId, 0, MAC.fromString(routerIfPortMac),
                    portWithPeerId)))
    }
}

@RunWith(classOf[JUnitRunner])
class RouterGatewayPortTranslationTest extends PortTranslatorTest{
    before {
        initMockStorage()
        translator = new PortTranslator(stateTableStorage, seqDispenser)

        bind(networkId, nNetworkBase)
        bind(portId, midoPortBaseUp)
        bind(portId, nGatewayPortWithVPN)
        bind(mrGatewayPortId, mrGatewayPort)
        bind(outSnatRuleId, null, classOf[Rule])
        bind(networkId, midoNetwork)
        bind(routerIdWithVPN, mRouterWithGwPortWithVPN)
        bind(routerIdWithoutVPN, mRouterWithGwPortWithoutVPN)
        bind(chainId, mRedirectChain)
        bindAll(Seq(espRuleId, udp500RuleId, udp4500RuleId),
                Seq(mEspRuleId, mUdp500RuleId, mUdp4500RuleId))
        bindAll(Seq(nVpnServiceId), Seq(nVpnService))
        bindAll(Seq(), Seq(), classOf[IPAddrGroup])
    }

    private val externalIp = IPv4Addr.random
    private val routerIdWithVPN = randomUuidProto
    private val chainId = randomUuidProto

    private val espRuleId = randomUuidProto
    private val mEspRuleId = mRuleFromTxt(s"""
        id: { $espRuleId }
        chain_id: { $chainId }
        type: L2TRANSFORM_RULE
        action: REDIRECT
        condition {
            nw_dst_ip {
                version: V4
                address: "${externalIp.toString}"
                prefix_length: 32
            }
            nw_proto: 50
        }""")

    private val udp500RuleId = randomUuidProto
    private val mUdp500RuleId = mRuleFromTxt(s"""
        id: { $udp500RuleId }
        chain_id: { $chainId }
        type: L2TRANSFORM_RULE
        action: REDIRECT
        condition {
            nw_dst_ip {
                version: V4
                address: "${externalIp.toString}"
                prefix_length: 32
            }
            nw_proto: 17
            tp_dst: {
                start: 500
                end: 500
            }
        }
        """)

    private val udp4500RuleId = randomUuidProto
    private val mUdp4500RuleId = mRuleFromTxt(s"""
        id: { $udp4500RuleId }
        chain_id: { $chainId }
        type: L2TRANSFORM_RULE
        action: REDIRECT
        condition {
            nw_dst_ip {
                version: V4
                address: "${externalIp.toString}"
                prefix_length: 32
            }
            nw_proto: 17
            tp_dst: {
                start: 4500
                end: 4500
            }
        }
        """)

    private val mRedirectChain = mChainFromTxt(s"""
        id { $chainId }
        rule_ids { $espRuleId }
        rule_ids { $udp500RuleId }
        rule_ids { $udp4500RuleId }
        """)

    private val nVpnServiceId = randomUuidProto
    private val nVpnService = nVpnServiceFromTxt(s"""
        id { $nVpnServiceId }
        """)

    private val mRouterWithGwPortWithVPN = mRouterFromTxt(s"""
        id { $routerIdWithVPN }
        vpn_service_ids { $nVpnServiceId }
        local_redirect_chain_id { $chainId }
        """)

    private val nGatewayPortWithVPN = nPortFromTxt(portBaseUp + s"""
        device_owner: ROUTER_GATEWAY
        device_id: '${fromProto(routerIdWithVPN)}'
        fixed_ips {
          ip_address {
            version: V4
            address: '${externalIp.toString}'
          }
        }
        """)

    private val routerIdWithoutVPN = randomUuidProto
    private val mRouterWithGwPortWithoutVPN = mRouterFromTxt(s"""
        id { $routerIdWithVPN }
        """)
    private val nGatewayPortWithoutVPN = nPortFromTxt(portBaseUp + s"""
        device_owner: ROUTER_GATEWAY
        device_id: '${fromProto(routerIdWithoutVPN)}'
        fixed_ips {
          ip_address {
            version: V4
            address: '${externalIp.toString}'
          }
        }
        """)

    private val mrGatewayPortId = RouterTranslator.tenantGwPortId(portId)
    private val mrGatewayPort = mPortFromTxt(s"""
        id: { $mrGatewayPortId }
        router_id: { $routerIdWithVPN }
        """)
    private val outSnatRuleId = RouterTranslator.outSnatRuleId(routerIdWithVPN)

    "Router gateway port CREATE" should "produce Mido provider router port " +
    "CREATE" in {
        // TODO: Test that the midoPort has the provider router ID.
    }

    "Router gateway port UPDATE" should "not update Port without VPN service" in {
        translator.translate(transaction, UpdateOp(nGatewayPortWithoutVPN))
        midoOps shouldBe empty
    }

    "Router gateway port UPDATE" should "update redirect rules if VPN service exists" in {
        val newExternalIp = IPSubnetUtil.fromAddress(IPv4Addr.random)
        val newGwPort = nPortFromTxt(portBaseUp + s"""
            device_owner: ROUTER_GATEWAY
            device_id: '${fromProto(routerIdWithVPN)}'
            fixed_ips {
              ip_address {
                version: V4
                address: '${newExternalIp.getAddress}'
              }
            }
            """)
        translator.translate(transaction, UpdateOp(newGwPort))
        midoOps should have size 6

        midoOps should contain (UpdateOp(nVpnService
                                             .toBuilder
                                             .setExternalIp(newExternalIp.getAddress)
                                             .build))
        val newEspRule = mEspRuleId.toBuilder
        newEspRule.getConditionBuilder.setNwDstIp(newExternalIp)
        midoOps should contain (UpdateOp(newEspRule.build))

        val newUdp500Rule = mUdp500RuleId.toBuilder
        newUdp500Rule.getConditionBuilder.setNwDstIp(newExternalIp)
        midoOps should contain (UpdateOp(newUdp500Rule.build))

        val newUdp4500Rule = mUdp4500RuleId.toBuilder
        newUdp4500Rule.getConditionBuilder.setNwDstIp(newExternalIp)
        midoOps should contain (UpdateOp(newUdp4500Rule.build))
    }

    "Router gateway port  DELETE" should "delete the MidoNet Port" in {
        translator.translate(transaction,
                             DeleteOp(classOf[NeutronPort], portId))
        midoOps should contain (DeleteOp(classOf[Port], portId))
    }
}

@RunWith(classOf[JUnitRunner])
class RemotePortTranslationTest extends PortTranslatorTest {

    val remotePort = nPortFromTxt(s"""
        $portBaseUp
        device_owner: REMOTE_SITE
        fixed_ips {
          ip_address {
            version: V4
            address: '$ipv4Addr1Txt'
          }
          subnet_id { $nIpv4Subnet1Id }
        }
        """)

    protected val remotePortArpEntryPath =
        stateTableStorage.bridgeArpEntryPath(networkId, IPv4Addr(ipv4Addr1Txt),
                                             MAC.fromString(mac))
    protected val remotePortMacEntryPath =
        stateTableStorage.bridgeMacEntryPath(networkId, 0, MAC.fromString(mac),
                                             l2gwNetworkPortId(networkId))

    before {
        initMockStorage()
        bind(networkId, nNetworkBase)
        bind(portId, remotePort)
        translator = new PortTranslator(stateTableStorage, seqDispenser)
    }

    "Remote port CREATE" should "only add ARP and MAC seedings" in {
        translator.translate(transaction, CreateOp(remotePort))

        midoOps should contain only(
            CreateNode(remotePortArpEntryPath),
            CreateNode(remotePortMacEntryPath))
    }

    "Remote port DELETE" should "remote ARP and MAC seedigns" in {
        translator.translate(transaction, DeleteOp(classOf[NeutronPort], portId))

        midoOps should contain only (
            DeleteNode(remotePortArpEntryPath),
            DeleteNode(remotePortMacEntryPath))
    }

}
