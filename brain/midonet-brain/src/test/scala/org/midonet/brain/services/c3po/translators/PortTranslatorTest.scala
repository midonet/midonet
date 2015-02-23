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

import scala.collection.JavaConverters._
import scala.concurrent.Promise

import com.google.protobuf.Message
import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.brain.services.c3po.C3POStorageManager.{OpType, Operation}
import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Chain, IpAddrGroup, Network, Port, Router, Rule}
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, randomUuidProto}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{ARP, IPv4, IPv6}

trait OpMatchers {
    /**
     * This attempts to match the expected Message against the actual one,
     * ignoring the "id" field when the expected message doesn't have the field
     * set. This is needed because often an ID of a model is assigned randomly.
     */
    class ContainsOp[M <: Message](expected: Operation[M])
    extends Matcher[List[Operation[M]]] {
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

        private def matchesOp(expected: Operation[M], actual: Operation[M]) = {
            if (expected.opType == actual.opType) {
                expected match {
                    case midonet.Create(m) =>
                        matchesModuloId(m,
                                actual.asInstanceOf[midonet.Create[M]].model)
                    case midonet.Update(m) =>
                        matchesModuloId(m,
                                actual.asInstanceOf[midonet.Update[M]].model)
                    case midonet.Delete(clazz, id) => expected == actual
                }
            } else false
        }

        def apply(left: List[Operation[M]]) =
            MatchResult(left.exists { matchesOp(expected, _) },
                        s"$left\n\ndoes not contain a matching operation\n\n" +
                        s"$expected",
                        s"$left\n\ncontains a matching operation\n\n$expected")
    }

    def containOp[M <: Message](expected: Operation[M]) =
        new ContainsOp[M](expected)
}

/* A common base class for testing NeutronPort CRUD translation. */
class PortTranslatorTest extends FlatSpec with BeforeAndAfter
                                          with Matchers
                                          with OpMatchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: PortTranslator = _

    protected val portId = randomUuidProto
    protected val portJUuid = UUIDUtil.fromProto(portId)
    protected val networkId = randomUuidProto
    protected val tenantId = "neutron tenant"
    protected val mac = "00:11:22:33:44:55"

    protected val portBase = s"""
        id { $portId }
        network_id { $networkId }
        tenant_id: 'neutron tenant'
        mac_address: '$mac'
        """
    protected val portBaseDown = portBase + """
        admin_state_up: false
        """
    protected val portBaseUp = portBase + """
        admin_state_up: true
        """

    protected val vifPortUp = nPortFromTxt(portBaseUp)

    val midoNetworkBase = s"""
        id { $networkId }
        """
    val midoNetwork = mNetworkFromTxt(midoNetworkBase)

    private val midoPortBase = s"""
        id { $portId }
        network_id { $networkId }
        """
    val midoPortBaseUp = mPortFromTxt(midoPortBase + """
        admin_state_up: true
        """)
    val midoPortBaseDown = mPortFromTxt(midoPortBase + """
        admin_state_up: false
        """)

    val inboundChainId = portId.nextUuid
    val outboundChainId = inboundChainId.nextUuid
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

    val midoNetworkWithSubnets = s"""
            $midoNetworkBase
            dhcp_subnets {
              subnet_address {
                version: V4
                address: "$ipv4Subnet1Addr"
                prefix_length: 8
              }
            }
            dhcp_subnets {
              subnet_address {
                version: V6
                address: "$ipv6Addr1Txt"
                prefix_length: 64
              }
            }"""
    val mNetworkWithSubnets = mNetworkFromTxt(midoNetworkWithSubnets)

    val nIpv4Subnet1Id = randomUuidProto
    val nIpv4Subnet1 = nSubnetFromTxt(s"""
        id { $nIpv4Subnet1Id }
        network_id { $networkId }
        cidr: '$ipv4Subnet1Cidr'
        ip_version: 4
        """)

    val nIpv6Subnet1Id = randomUuidProto
    val nIpv6Subnet1= nSubnetFromTxt(s"""
        id { $nIpv6Subnet1Id }
        network_id { $networkId }
        cidr: '$ipv6Subnet1Cidr'
        ip_version: 6
        """)

    /* Finds an operation on Chain with the specified chain ID, and returns a
     * first one found.
     */
    protected def findChainOp(
            ops: List[Operation[Message]], op: OpType.OpType, chainId: UUID) = {
        ops.collectFirst {
            case midonet.Create(c: Chain)
                    if c.getId == chainId && op == OpType.Create => c
            case midonet.Update(c: Chain)
                    if c.getId == chainId && op == OpType.Update => c
        }.orNull
    }

    /* Finds an operation on Router with the specified chain ID, and returns a
     * first one found.
     */
    protected def findRouterOp(
            ops: List[Operation[Message]], op: OpType.OpType, rtrId: UUID) = {
        ops.collectFirst {
            case midonet.Create(c: Router)
                    if c.getId == rtrId && op == OpType.Create => c
            case midonet.Update(c: Router)
                    if c.getId == rtrId && op == OpType.Update => c
        }.orNull
    }
}

/* Contains common constructs for testing VIF port CRUD translation. */
class VifPortTranslationTest extends PortTranslatorTest {
    val sgId1 = randomUuidProto
    val sgId2 = randomUuidProto

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

    val vifPortWithFipsAndSgs = nPortFromTxt(s"""
        $vifPortWithFixedIps
        security_groups { $sgId1 }
        security_groups { $sgId2 }
        """)

    val ipAddrGroup1InChainId = randomUuidProto
    val ipAddrGroup1OutChainId = randomUuidProto
    val ipAddrGroup1 = mIpAddrGroupFromTxt(s"""
        id { $sgId1 }
        inbound_chain_id { $ipAddrGroup1InChainId }
        outbound_chain_id { $ipAddrGroup1OutChainId }
        """)
    val ipAddrGroup2InChainId = randomUuidProto
    val ipAddrGroup2OutChainId = randomUuidProto
    val ipAddrGroup2 = mIpAddrGroupFromTxt(s"""
        id { $sgId2 }
        inbound_chain_id { $ipAddrGroup2InChainId }
        outbound_chain_id { $ipAddrGroup2OutChainId }
        """)

    val mNetworkWithHostsAdded = mNetworkFromTxt(s"""
        $midoNetworkBase
        dhcp_subnets {
          subnet_address {
            version: V4
            address: "127.0.0.0"
            prefix_length: 8
          }
          hosts {
            mac: "$mac"
            ip_address {
              version: V4
              address: "$ipv4Addr1Txt"
            }
          }
        }
        dhcp_subnets {
          subnet_address {
            version: V6
            address: "$ipv6Addr1Txt"
            prefix_length: 64
          }
          hosts {
            mac: "$mac"
            ip_address {
              version: V6
              address: "$ipv6Addr1Txt"
            }
          }
        }""")
}

/**
 * Tests the Neutron VIF Port model Create translation.
 */
@RunWith(classOf[JUnitRunner])
class VifPortCreateTranslationTest extends VifPortTranslationTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[NeutronSubnet], nIpv4Subnet1Id))
            .thenReturn(Promise.successful(nIpv4Subnet1).future)
        when(storage.get(classOf[NeutronSubnet], nIpv6Subnet1Id))
            .thenReturn(Promise.successful(nIpv6Subnet1).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(mNetworkWithSubnets).future)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortBaseUp).future)
        when(storage.get(classOf[IpAddrGroup], sgId1))
            .thenReturn(Promise.successful(ipAddrGroup1).future)
        when(storage.get(classOf[IpAddrGroup], sgId2))
            .thenReturn(Promise.successful(ipAddrGroup2).future)
    }

    "Fixed IPs for a new VIF port" should "create DHCP subnets" in {
        val midoOps = translator.translate(neutron.Create(vifPortWithFixedIps))

        midoOps should contain (midonet.Update(mNetworkWithHostsAdded))
    }

    "A created VIF port" should "have security bindings" in {

        val midoOps: List[Operation[Message]] =
            translator.translate(neutron.Create(vifPortWithFipsAndSgs))
                      .asInstanceOf[List[Operation[Message]]]

        midoOps should contain (midonet.Create(mPortWithChains))

        // Security bindings.
        val inboundChain = mChainFromTxt(s"""
            id { $inboundChainId }
            name: "OS_PORT_${portJUuid}_INBOUND"
            """)
        val outboundChain = mChainFromTxt(s"""
            id { $outboundChainId }
            name: "OS_PORT_${portJUuid}_OUTBOUND"
            """)

        val revFlowRuleOutbound = mRuleFromTxt(s"""
            type: NAT_TYPE
            action: ACCEPT
            match_return_flow: true
            chain_id { $outboundChainId }
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            type: NAT_TYPE
            action: ACCEPT
            match_return_flow: true
            chain_id { $inboundChainId }
            """)

        val ipSpoofProtectIpv4 = mRuleFromTxt(s"""
            type: LITERAL_TYPE
            chain_id { $inboundChainId }
            nw_src_ip: { $ipv4Subnet1 }
            dl_type: ${IPv4.ETHERTYPE}
            nw_src_inv: true
            fragment_policy: ANY
            action: DROP
            """)

        val ipSpoofProtectIpv6 = mRuleFromTxt(s"""
            type: LITERAL_TYPE
            action: DROP
            chain_id { $inboundChainId }
            nw_src_ip: { $ipv6Subnet1 }
            dl_type: ${IPv6.ETHERTYPE}
            nw_src_inv: true
            fragment_policy: ANY
            """)

        val macSpoofProtect = mRuleFromTxt(s"""
            type: LITERAL_TYPE
            action: DROP
            chain_id { $inboundChainId }
            dl_src: '$mac'
            inv_dl_src: true
            fragment_policy: ANY
            """)

         val jumpRuleIn1 = mRuleFromTxt(s"""
            type: JUMP_TYPE
            action: JUMP
            chain_id { $inboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup1InChainId }
            }
            """)

        val jumpRuleIn2 = mRuleFromTxt(s"""
            type: JUMP_TYPE
            action: JUMP
            chain_id { $inboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup2InChainId }
            }
            """)

        val jumpRuleOut1 = mRuleFromTxt(s"""
            type: JUMP_TYPE
            action: JUMP
            chain_id { $outboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup1OutChainId }
            }
            """)

        val jumpRuleOut2 = mRuleFromTxt(s"""
            type: JUMP_TYPE
            action: JUMP
            chain_id { $outboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup2OutChainId }
            }
            """)

        val dropNonArpIn = mRuleFromTxt(s"""
            type: LITERAL_TYPE
            chain_id { $inboundChainId }
            action: DROP
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        val dropNonArpOut = mRuleFromTxt(s"""
            type: LITERAL_TYPE
            chain_id { $outboundChainId }
            action: DROP
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        val inChain = findChainOp(midoOps, OpType.Create, inboundChainId)
        inChain should not be (null)
        inChain.getName shouldBe (s"OS_PORT_${portJUuid}_INBOUND")
        inChain.getRuleIdsList.size shouldBe (7)

        val outChain= findChainOp(midoOps, OpType.Create, outboundChainId)
        outChain should not be (null)
        outChain.getName shouldBe (s"OS_PORT_${portJUuid}_OUTBOUND")
        outChain.getRuleIdsList.size shouldBe (4)

        midoOps should containOp[Message] (midonet.Create(revFlowRuleOutbound))
        midoOps should containOp[Message] (midonet.Create(ipSpoofProtectIpv4))
        midoOps should containOp[Message] (midonet.Create(ipSpoofProtectIpv6))
        midoOps should containOp[Message] (midonet.Create(macSpoofProtect))
        midoOps should containOp[Message] (midonet.Create(revFlowRuleInbound))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn2))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut2))
        midoOps should containOp[Message] (midonet.Create(dropNonArpIn))
        midoOps should containOp[Message] (midonet.Create(dropNonArpOut))

        // IP Address Groups.
        val ipAddrGrp1 = mIpAddrGroupFromTxt(s"""
            id { $sgId1 }
            ip_addr_ports {
                ip_address { $ipv4Addr1 }
                port_id { $portId }
            }
            ip_addr_ports {
                ip_address { $ipv6Addr1 }
                port_id { $portId }
            }
            inbound_chain_id { $ipAddrGroup1InChainId }
            outbound_chain_id { $ipAddrGroup1OutChainId }
            """)
        val ipAddrGrp2 = mIpAddrGroupFromTxt(s"""
            id { $sgId2 }
            ip_addr_ports {
                ip_address { $ipv4Addr1 }
                port_id { $portId }
            }
            ip_addr_ports {
                ip_address { $ipv6Addr1 }
                port_id { $portId }
            }
            inbound_chain_id { $ipAddrGroup2InChainId }
            outbound_chain_id { $ipAddrGroup2OutChainId }
            """)
        midoOps should contain (midonet.Update(ipAddrGrp1))
        midoOps should contain (midonet.Update(ipAddrGrp1))
    }

    // TODO test that VIF port CREATE creates an external network route if the
    //      port is attached to an external network.
    // TODO test that VIF/DHCP/interface/router GW port CREATE creates a tunnel
    //      key, and makes the tunnel key reference the port.
}

/**
 * Tests the Neutron VIF Port model Update / Delete.
 */
@RunWith(classOf[JUnitRunner])
class VifPortUpdateDeleteTranslationTest extends VifPortTranslationTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[NeutronSubnet], nIpv4Subnet1Id))
            .thenReturn(Promise.successful(nIpv4Subnet1).future)
        when(storage.get(classOf[NeutronSubnet], nIpv6Subnet1Id))
            .thenReturn(Promise.successful(nIpv6Subnet1).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(mNetworkWithHostsAdded).future)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(mPortWithChains).future)
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFixedIps).future)
        when(storage.get(classOf[IpAddrGroup], sgId1))
            .thenReturn(Promise.successful(ipAddrGroup1).future)
        when(storage.get(classOf[IpAddrGroup], sgId2))
            .thenReturn(Promise.successful(ipAddrGroup2).future)
        when(storage.get(classOf[Chain], inboundChainId))
            .thenReturn(Promise.successful(inboundChain).future)
        when(storage.get(classOf[Chain], outboundChainId))
            .thenReturn(Promise.successful(outboundChain).future)
    }

    "VIF port UPDATE" should "update port admin state" in {

        val vifPortDown = nPortFromTxt(s"$portBaseDown")
        val midoOps = translator.translate(neutron.Update(vifPortDown))
        midoOps should contain (midonet.Update(mPortDownWithChains))
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
        val midoOps = translator.translate(neutron.Update(vifPortWithFixedIps2))
        val networkWithUpdatedDhcpHosts = mNetworkFromTxt(s"""
            $midoNetworkBase
            dhcp_subnets {
              subnet_address {
                version: V4
                address: "$ipv4Subnet1Addr"
                prefix_length: 8
              }
              hosts {
                mac: "$mac"
                ip_address {
                  version: V4
                  address: "$updatedFixedIpTxt"
                }
              }
            }
            dhcp_subnets {
              subnet_address {
                version: V6
                address: "$ipv6Addr1Txt"
                prefix_length: 64
              }
            }""")
        midoOps should contain (midonet.Update(networkWithUpdatedDhcpHosts))
    }

    val vifPortWithFipsAndSgs2 = nPortFromTxt(s"""
        $vifPortWithFixedIps2
        security_groups { $sgId1 }
        security_groups { $sgId2 }
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

    "UPDATE VIF port with fixed IPs" should "update security rules" in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFipsAndSgs).future)

        val midoOps: List[Operation[Message]] =
            translator.translate(neutron.Update(vifPortWithFipsAndSgs2))
                      .asInstanceOf[List[Operation[Message]]]

        val revFlowRuleOutbound = mRuleFromTxt(s"""
            type: NAT_TYPE
            action: ACCEPT
            match_return_flow: true
            chain_id { $outboundChainId }
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            type: NAT_TYPE
            action: ACCEPT
            match_return_flow: true
            chain_id { $inboundChainId }
            """)

        val ipSpoofProtectIpv4 = mRuleFromTxt(s"""
            type: LITERAL_TYPE
            action: DROP
            chain_id { $inboundChainId }
            dl_type: ${IPv4.ETHERTYPE}
            nw_src_ip: { $ipv4Subnet1 }
            nw_src_inv: true
            fragment_policy: ANY
            """)

        val macSpoofProtect = mRuleFromTxt(s"""
            action: DROP
            type: LITERAL_TYPE
            chain_id { $inboundChainId }
            dl_src: '$mac'
            inv_dl_src: true
            fragment_policy: ANY
            """)

        val jumpRuleIn1 = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_TYPE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup1InChainId }
            }
            """)

        val jumpRuleIn2 = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_TYPE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup2InChainId }
            }
            """)

        val jumpRuleOut1 = mRuleFromTxt(s"""
            chain_id { $outboundChainId }
            type: JUMP_TYPE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup1OutChainId }
            }
            """)

        val jumpRuleOut2 = mRuleFromTxt(s"""
            chain_id { $outboundChainId }
            type: JUMP_TYPE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup2OutChainId }
            }
            """)

        val dropNonArpIn = mRuleFromTxt(s"""
            action: DROP
            type: LITERAL_TYPE
            chain_id { $inboundChainId }
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        val dropNonArpOut = mRuleFromTxt(s"""
            action: DROP
            type: LITERAL_TYPE
            chain_id { $outboundChainId }
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule1))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule2))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule3))
        midoOps should contain (midonet.Delete(classOf[Rule], outChainRule1))
        midoOps should containOp[Message] (midonet.Create(revFlowRuleOutbound))
        midoOps should containOp[Message] (midonet.Create(ipSpoofProtectIpv4))
        midoOps should containOp[Message] (midonet.Create(macSpoofProtect))
        midoOps should containOp[Message] (midonet.Create(revFlowRuleInbound))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn2))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut2))
        midoOps should containOp[Message] (midonet.Create(dropNonArpIn))
        midoOps should containOp[Message] (midonet.Create(dropNonArpOut))

        val inChain = findChainOp(midoOps, OpType.Update, inboundChainId)
        inChain should not be (null)
        inChain.getName shouldBe (s"OS_PORT_${portJUuid}_INBOUND")
        inChain.getRuleIdsList.size shouldBe (6)

        val outChain= findChainOp(midoOps, OpType.Update, outboundChainId)
        outChain should not be (null)
        outChain.getName shouldBe (s"OS_PORT_${portJUuid}_OUTBOUND")
        outChain.getRuleIdsList.size shouldBe (4)

        val ipAddrGrp1 = mIpAddrGroupFromTxt(s"""
            id { $sgId1 }
            ip_addr_ports {
                ip_address { $updatedFixedIp }
                port_id { $portId }
            }
            inbound_chain_id { $ipAddrGroup1InChainId }
            outbound_chain_id { $ipAddrGroup1OutChainId }
            """)
        val ipAddrGrp2 = mIpAddrGroupFromTxt(s"""
            id { $sgId2 }
            ip_addr_ports {
                ip_address { $ipv4Addr1 }
                port_id { $portId }
            }
            inbound_chain_id { $ipAddrGroup2InChainId }
            outbound_chain_id { $ipAddrGroup2OutChainId }
            """)
        midoOps should contain (midonet.Update(ipAddrGrp1))
        midoOps should contain (midonet.Update(ipAddrGrp1))
    }

    "DELETE VIF port with fixed IPs" should "delete the MidoNet Port" in {
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
    }

    "DELETE VIF port with fixed IPs" should "delete DHCP host entries and " +
    "chains." in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFipsAndSgs).future)

        val midoOps = translator.translate(neutron.Delete(classOf[NeutronPort],
                                                          portId))
        val networkWithNoDhcpHosts = mNetworkFromTxt(s"""
            $midoNetworkBase
            dhcp_subnets {
              subnet_address {
                version: V4
                address: "$ipv4Subnet1Addr"
                prefix_length: 8
              }
            }
            dhcp_subnets {
              subnet_address {
                version: V6
                address: "$ipv6Addr1Txt"
                prefix_length: 64
              }
            }""")

        midoOps should contain (midonet.Update(networkWithNoDhcpHosts))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule1))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule2))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule3))
        midoOps should contain (midonet.Delete(classOf[Chain], inboundChainId))
        midoOps should contain (midonet.Delete(classOf[Chain], outboundChainId))

        val ipAddrGrp1 = mIpAddrGroupFromTxt(s"""
            id { $sgId1 }
            inbound_chain_id { $ipAddrGroup1InChainId }
            outbound_chain_id { $ipAddrGroup1OutChainId }
            """)
        val ipAddrGrp2 = mIpAddrGroupFromTxt(s"""
            id { $sgId2 }
            inbound_chain_id { $ipAddrGroup2InChainId }
            outbound_chain_id { $ipAddrGroup2OutChainId }
            """)
        midoOps should contain (midonet.Update(ipAddrGrp1))
        midoOps should contain (midonet.Update(ipAddrGrp1))
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

    protected val portWithPeerId = randomUuidProto
    protected val peerRouterPortId = randomUuidProto

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
        gw_port_id { $peerRouterPortId }
        """)

    protected val mNetworkWithDhcpPort = mNetworkFromTxt(s"""
        $midoNetworkWithSubnets
        port_ids { $portWithPeerId }
        """)

    protected val mNetworkWithDhcpConfigured = mNetworkFromTxt(s"""
        $midoNetworkBase
        dhcp_subnets {
          subnet_address {
            version: V4
            address: "$ipv4Subnet1Addr"
            prefix_length: 8
          }
          server_address {
            version: V4
            address: '$ipv4Addr1Txt'
          }
          opt121_routes {
            dst_subnet {
              version: V4
              address: "169.254.169.254"
              prefix_length: 32
            }
            gateway {
              version: V4
              address: '$ipv4Addr1Txt'
            }
          }
        }
        dhcp_subnets {
          subnet_address {
            version: V6
            address: "$ipv6Addr1Txt"
            prefix_length: 64
          }
        }
        port_ids { $portWithPeerId }
        """)
}

@RunWith(classOf[JUnitRunner])
class DhcpPortCreateTranslationTest extends DhcpPortTranslationTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(mNetworkWithDhcpPort).future)
        when(storage.get(classOf[NeutronSubnet], nIpv4Subnet1Id))
            .thenReturn(Promise.successful(nIpv4Subnet1WithGwIP).future)
        when(storage.get(classOf[NeutronSubnet], nIpv6Subnet1Id))
            .thenReturn(Promise.successful(nIpv6Subnet1).future)
        when(storage.get(classOf[Port], portWithPeerId))
            .thenReturn(Promise.successful(mPortWithRPortPeer).future)
        when(storage.get(classOf[Port], peerRouterPortId))
            .thenReturn(Promise.successful(mRouterPort).future)
        when(storage.get(classOf[Router], routerId))
            .thenReturn(Promise.successful(mRouterWithGwPort).future)
    }

    "DHCP port CREATE" should "configure DHCP" in {
        val midoOps: List[Operation[Message]] =
            translator.translate(neutron.Create(dhcpPort))
                      .asInstanceOf[List[Operation[Message]]]

        midoOps should contain (midonet.Create(midoPortBaseUp))
        midoOps should contain (midonet.Update(mNetworkWithDhcpConfigured))

        val routerOp = findRouterOp(midoOps, OpType.Update, routerId)
        routerOp should not be (null)
        routerOp.getRoutesCount shouldBe 1
        val route = routerOp.getRoutes(0)
        route shouldBe mRouteFromTxt(s"""
            id { ${route.getId} }
            src_subnet { $ipv4Subnet1 }
            dst_subnet { ${IPSubnetUtil.toProto("169.254.169.254/32")} }
            next_hop: PORT
            next_hop_port_id { $peerRouterPortId }
            next_hop_gateway { $ipv4Addr1 }
            weight: 100
            router_id { $routerId }
            """)
    }
}

@RunWith(classOf[JUnitRunner])
class DhcpPortUpdateDeleteTranslationTest extends DhcpPortTranslationTest {
    protected val mRouterWithRoute = mRouterFromTxt(s"""
        id { $routerId }
        gw_port_id { $peerRouterPortId }
        routes {
            id { $randomUuidProto }
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
            router_id { $routerId }
        }
        """)
    protected val mRouterWithMDSRoute = mRouterFromTxt(s"""
        $mRouterWithRoute
        routes {
            id { $randomUuidProto }
            src_subnet { $ipv4Subnet1 }
            dst_subnet { ${IPSubnetUtil.toProto("169.254.169.254/32")} }
            next_hop: PORT
            next_hop_port_id { $peerRouterPortId }
            next_hop_gateway { $ipv4Addr1 }
            weight: 100
            router_id { $routerId }
        }
        """)
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortBaseUp).future)
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(dhcpPort).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(mNetworkWithDhcpConfigured).future)
        when(storage.get(classOf[NeutronSubnet], nIpv4Subnet1Id))
            .thenReturn(Promise.successful(nIpv4Subnet1WithGwIP).future)
        when(storage.get(classOf[NeutronSubnet], nIpv6Subnet1Id))
            .thenReturn(Promise.successful(nIpv6Subnet1).future)
        when(storage.get(classOf[Port], portWithPeerId))
            .thenReturn(Promise.successful(mPortWithRPortPeer).future)
        when(storage.get(classOf[Port], peerRouterPortId))
            .thenReturn(Promise.successful(mRouterPort).future)
        when(storage.get(classOf[Router], routerId))
            .thenReturn(Promise.successful(mRouterWithMDSRoute).future)
    }

    "DHCP port UPDATE" should "update port admin state" in {
        val dhcpPortDown = dhcpPort.toBuilder().setAdminStateUp(false).build
        val midoOps = translator.translate(neutron.Update(dhcpPortDown))

        midoOps should contain (midonet.Update(midoPortBaseDown))
    }

    // TODO Add an assert that the fixed IPs haven't been changed.

    "DHCP port  DELETE" should "delete the MidoNet Port" in {
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
        midoOps should contain (midonet.Update(mNetworkWithDhcpPort))
        midoOps should contain (midonet.Update(mRouterWithRoute))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpPortTranslationTest extends PortTranslatorTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortBaseUp).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(midoNetwork).future)
    }

    protected val fipPortUp = nPortFromTxt(portBaseUp + """
        device_owner: FLOATINGIP
        """)

    "Floating IP port CREATE" should "not create a Network port" in {
        val midoOps = translator.translate(neutron.Create(fipPortUp))
        midoOps shouldBe empty
    }

    "Floating IP port UPDATE" should "NOT update Port" in {
        val midoOps = translator.translate(neutron.Update(fipPortUp))
        midoOps shouldBe empty
     }

    "Floating IP DELETE" should "NOT delete the MidoNet Port" in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(fipPortUp).future)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps shouldBe empty
    }
}

@RunWith(classOf[JUnitRunner])
class RouterInterfacePortTranslationTest extends PortTranslatorTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortBaseUp).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(midoNetwork).future)
    }

    private val routerIfPortUp = nPortFromTxt(portBaseUp + """
        device_owner: ROUTER_INTERFACE
        """)

    "Router interface port CREATE" should "create a normal Network port" in {
        val midoOps = translator.translate(neutron.Create(routerIfPortUp))
        midoOps should contain only midonet.Create(midoPortBaseUp)
    }

    "Router interface port UPDATE" should "NOT update Port" in {
        val midoOps = translator.translate(neutron.Update(routerIfPortUp))
        midoOps shouldBe empty
     }

    "Router interface port DELETE" should "delete the MidoNet Port" in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(routerIfPortUp).future)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
    }
}

@RunWith(classOf[JUnitRunner])
class RouterGatewayPortTranslationTest extends PortTranslatorTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortBaseUp).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(midoNetwork).future)
    }

    private val routerGatewayPort = nPortFromTxt(portBaseUp + """
        device_owner: ROUTER_GATEWAY
        """)

    "Router gateway port CREATE" should "produce Mido provider router port " +
    "CREATE" in {
        // TODO: Test that the midoPort has the provider router ID.
    }

    "Router gateway port UPDATE" should "not update Port " +
    "CREATE" in {
        val midoOps = translator.translate(neutron.Update(routerGatewayPort))
        midoOps shouldBe empty
    }

    "Router gateway port  DELETE" should "delete the MidoNet Port" in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(routerGatewayPort).future)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
    }
}