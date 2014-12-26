/*
 * Copyright 2014 Midokura SARL
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

import java.io.StringReader

import scala.collection.JavaConverters._
import scala.concurrent.Promise

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, when}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.junit.JUnitRunner

import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.brain.services.c3po.C3POStorageManager.{OpType, Operation}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Chain, IpAddrGroup, Network, Port, Rule}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.{ARP, IPv4, IPv6}

trait OpMatchers {
    class ContainsOp[M <: Message](expected: Operation[M])
    extends Matcher[List[Operation[M]]] {
        private def matchesModuloId(expected: Message, actual: Message) = {
            if (actual.getDescriptorForType.equals(
                    expected.getDescriptorForType)) {
                val hasExpectedFields =
                    expected.getAllFields.entrySet.asScala.forall(fld =>
                        actual.hasField(fld.getKey) &&
                        actual.getField(fld.getKey) == fld.getValue
                        )

                val noExtraFields =
                    !actual.getAllFields.keySet.asScala
                           .diff(expected.getAllFields.keySet.asScala)
                           .exists(_.getName != "id")
                          // Allow an Rule ID to be set on the actual rule.

                hasExpectedFields && noExtraFields
            } else false
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
                    case midonet.Delete(clazz, id) =>
                        val actualOp = actual.asInstanceOf[midonet.Delete[M]]
                        clazz == actualOp.clazz && id == actualOp.id
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

    protected val portId = UUIDUtil.randomUuidProto
    protected val portJUuid = UUIDUtil.fromProto(portId)
    protected val networkId = UUIDUtil.randomUuidProto
    protected val tenantId = "neutron tenant"
    protected val adminStateUp = true
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

    val midoPortBase = s"""
        id { $portId }
        network_id { $networkId }
        """
    val midoPortUp = mPortFromTxt(midoPortBase + """
        admin_state_up: true
        """)
    val midoPortDown = mPortFromTxt(midoPortBase + """
        admin_state_up: false
        """)

    def findChainOp(ops: List[Operation[Message]], op: OpType.OpType, chainId: UUID) = {
        var chain: Chain = null
        ops.find(_  match {
                case midonet.Create(c: Chain)
                        if c.getId == chainId && op == OpType.Create =>
                    chain = c
                    true
                case midonet.Update(c: Chain)
                        if c.getId == chainId && op == OpType.Update =>
                    chain = c
                    true
                case _ => false
        })
        chain
    }
}

/**
 * Tests the Neutron VIF Port model translation.
 */
@RunWith(classOf[JUnitRunner])
class VifPortTranslationTest extends PortTranslatorTest {
    val subnetAddr1 = "127.0.0.0"
    val subnetCidr1 = s"$subnetAddr1/8"
    val ipv4AddrTxt = "127.0.0.1"
    val ipv4Addr = IPAddressUtil.toProto(ipv4AddrTxt)
    val ipv4Subnet1 = IPSubnetUtil.toProto(subnetCidr1)

    val subnetCidr2 = "fe80:0:0:0:0:202:b3ff:8329/64"
    val ipv6AddrTxt = "fe80:0:0:0:0:202:b3ff:8329"
    val ipv6Addr = IPAddressUtil.toProto(ipv6AddrTxt)
    val ipv6Subnet2 = IPSubnetUtil.toProto(subnetCidr2)

    val midoNetworkWithSubnets = s"""
            $midoNetworkBase
            dhcp_subnets {
              subnet_address {
                version: V4
                address: "$subnetAddr1"
                prefix_length: 8
              }
            }
            dhcp_subnets {
              subnet_address {
                version: V6
                address: "$ipv6AddrTxt"
                prefix_length: 64
              }
            }"""
    val mNetworkWithSubnets = mNetworkFromTxt(midoNetworkWithSubnets)

    val subnetId1 = UUIDUtil.randomUuidProto
    val subnet1 = nSubnetFromTxt(s"""
        id { $subnetId1 }
        network_id { $networkId }
        cidr: '$subnetCidr1'
        ip_version: 4
        """)

    val subnetId2 = UUIDUtil.randomUuidProto
    val subnet2= nSubnetFromTxt(s"""
        id { $subnetId2 }
        network_id { $networkId }
        cidr: '$subnetCidr2'
        ip_version: 6
        """)

    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(mNetworkWithSubnets).future)
        when(storage.get(classOf[NeutronSubnet], subnetId1))
            .thenReturn(Promise.successful(subnet1).future)
        when(storage.get(classOf[NeutronSubnet], subnetId2))
            .thenReturn(Promise.successful(subnet2).future)
    }

    val sgId1 = UUIDUtil.randomUuidProto
    val sgId2 = UUIDUtil.randomUuidProto

    val vifPortWithFixedIps = nPortFromTxt(s"""
        $portBaseUp
        fixed_ips {
          ip_address {
            version: V4
            address: '$ipv4AddrTxt'
          }
          subnet_id { $subnetId1 }
        }
        fixed_ips {
          ip_address {
            version: V6
            address: '$ipv6AddrTxt'
          }
          subnet_id { $subnetId2 }
        }
        """)

    val vifPortWithFipsAndSgs = nPortFromTxt(s"""
        $vifPortWithFixedIps
        security_groups { $sgId1 }
        security_groups { $sgId2 }
        """)

    val ipAddrGroup1InChainId = UUIDUtil.randomUuidProto
    val ipAddrGroup1OutChainId = UUIDUtil.randomUuidProto
    val ipAddrGroup1 = mIpAddrGroupFromTxt(s"""
        id { $sgId1 }
        inbound_chain_id { $ipAddrGroup1InChainId }
        outbound_chain_id { $ipAddrGroup1OutChainId }
        """)
    val ipAddrGroup2InChainId = UUIDUtil.randomUuidProto
    val ipAddrGroup2OutChainId = UUIDUtil.randomUuidProto
    val ipAddrGroup2 = mIpAddrGroupFromTxt(s"""
        id { $sgId2 }
        inbound_chain_id { $ipAddrGroup2InChainId }
        outbound_chain_id { $ipAddrGroup2OutChainId }
        """)

    val networkWithDhcp = mNetworkFromTxt(s"""
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
              address: "$ipv4AddrTxt"
            }
          }
        }
        dhcp_subnets {
          subnet_address {
            version: V6
            address: "$ipv6AddrTxt"
            prefix_length: 64
          }
          hosts {
            mac: "$mac"
            ip_address {
              version: V6
              address: "$ipv6AddrTxt"
            }
          }
        }""")

    "Fixed IPs for a new VIF port" should "create DHCP subnets" in {
        val midoOps = translator.translate(neutron.Create(vifPortWithFixedIps))

        midoOps should contain (midonet.Update(networkWithDhcp))
    }

    val inboundChainId = portId.nextUuid
    val outboundChainId = inboundChainId.nextUuid
    val portWithChains = mPortFromTxt(s"""
        $midoPortUp
        inbound_filter_id { $inboundChainId }
        outbound_filter_id { $outboundChainId }
        """)

    "A created VIF port" should "have security bindings" in {
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortUp).future)
        when(storage.get(classOf[IpAddrGroup], sgId1))
            .thenReturn(Promise.successful(ipAddrGroup1).future)
        when(storage.get(classOf[IpAddrGroup], sgId2))
            .thenReturn(Promise.successful(ipAddrGroup2).future)

        val midoOps: List[Operation[Message]] =
            translator.translate(neutron.Create(vifPortWithFipsAndSgs))
                      .asInstanceOf[List[Operation[Message]]]

        midoOps should contain (midonet.Create(portWithChains))

        val inboundChain = mChainFromTxt(s"""
            id { $inboundChainId }
            name: "OS_PORT_${portJUuid}_INBOUND"
            """)
        val outboundChain = mChainFromTxt(s"""
            id { $outboundChainId }
            name: "OS_PORT_${portJUuid}_OUTBOUND"
            """)

        val revFlowRuleOutbound = mRuleFromTxt(s"""
            action: ACCEPT
            chain_id { $outboundChainId }
            match_return_flow: true
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            action: ACCEPT
            chain_id { $inboundChainId }
            match_return_flow: true
            """)
        val ipSpoofProtectIpv4 = mRuleFromTxt(s"""
            action: DROP
            chain_id { $inboundChainId }
            dl_type: ${IPv4.ETHERTYPE.asInstanceOf[Short]}
            nw_src_ip: { $ipv4Subnet1 }
            nw_src_inv: true
            fragment_policy: ANY
            """)
        val ipSpoofProtectIpv6 = mRuleFromTxt(s"""
            action: DROP
            chain_id { $inboundChainId }
            dl_type: ${IPv6.ETHERTYPE.asInstanceOf[Short]}
            nw_src_ip: { $ipv6Subnet2 }
            nw_src_inv: true
            fragment_policy: ANY
            """)
        val macSpoofProtect = mRuleFromTxt(s"""
            action: DROP
            chain_id { $inboundChainId }
            dl_src: '$mac'
            inv_dl_src: true
            fragment_policy: ANY
            """)
        val jumpRuleIn1 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $inboundChainId }
            jump_to { $ipAddrGroup1InChainId }
            """)
        val jumpRuleIn2 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $inboundChainId }
            jump_to { $ipAddrGroup2InChainId }
            """)
        val jumpRuleOut1 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $outboundChainId }
            jump_to { $ipAddrGroup1OutChainId }
            """)
        val jumpRuleOut2 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $outboundChainId }
            jump_to { $ipAddrGroup2OutChainId }
            """)
        val dropNonArpIn = mRuleFromTxt(s"""
            action: DROP
            chain_id { $inboundChainId }
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)
        val dropNonArpOut = mRuleFromTxt(s"""
            action: DROP
            chain_id { $outboundChainId }
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

        val ipAddrGrp1 = mIpAddrGroupFromTxt(s"""
            id { $sgId1 }
            ip_addr_ports {
                ip_address { $ipv4Addr }
                port_id { $portId }
            }
            ip_addr_ports {
                ip_address { $ipv6Addr }
                port_id { $portId }
            }
            inbound_chain_id { $ipAddrGroup1InChainId }
            outbound_chain_id { $ipAddrGroup1OutChainId }
            """)
        val ipAddrGrp2 = mIpAddrGroupFromTxt(s"""
            id { $sgId2 }
            ip_addr_ports {
                ip_address { $ipv4Addr }
                port_id { $portId }
            }
            ip_addr_ports {
                ip_address { $ipv6Addr }
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

    val portDown = mPortFromTxt(s"""
        $midoPortDown
        inbound_filter_id { $inboundChainId }
        outbound_filter_id { $outboundChainId }
        """)

    "VIF port UPDATE" should "update port admin state" in {
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(networkWithDhcp).future)
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFixedIps).future)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(portWithChains).future)
        when(storage.get(classOf[Chain], inboundChainId))
            .thenReturn(Promise.successful(inboundChain).future)
        when(storage.get(classOf[Chain], outboundChainId))
            .thenReturn(Promise.successful(outboundChain).future)

        val vifPortDown = nPortFromTxt(s"$portBaseDown")
        val midoOps = translator.translate(neutron.Update(vifPortDown))
        midoOps should contain (midonet.Update(portDown))
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
          subnet_id { $subnetId1 }
        }
        """)
    val vifPortWithFipsAndSgs2 = nPortFromTxt(s"""
        $vifPortWithFixedIps2
        security_groups { $sgId1 }
        security_groups { $sgId2 }
        """)

    val inChainRule1 = UUIDUtil.randomUuidProto
    val inChainRule2 = UUIDUtil.randomUuidProto
    val inChainRule3 = UUIDUtil.randomUuidProto
    val inboundChain = mChainFromTxt(s"""
        id { $inboundChainId }
        rule_ids { $inChainRule1 }
        rule_ids { $inChainRule2 }
        rule_ids { $inChainRule3 }
        """)
    val outChainRule1 = UUIDUtil.randomUuidProto
    val outboundChain = mChainFromTxt(s"""
        id { $outboundChainId }
        rule_ids { $outChainRule1 }
        """)

    "UPDATE VIF port with fixed IPs" should "delete old DHCP host entries " +
    "and create new DHCP host entries" in {
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(networkWithDhcp).future)
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFixedIps).future)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(portWithChains).future)
        when(storage.get(classOf[Chain], inboundChainId))
            .thenReturn(Promise.successful(inboundChain).future)
        when(storage.get(classOf[Chain], outboundChainId))
            .thenReturn(Promise.successful(outboundChain).future)

        val midoOps = translator.translate(neutron.Update(vifPortWithFixedIps2))
        val networkWithUpdatedDhcpHosts = mNetworkFromTxt(s"""
            $midoNetworkBase
            dhcp_subnets {
              subnet_address {
                version: V4
                address: "$subnetAddr1"
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
                address: "$ipv6AddrTxt"
                prefix_length: 64
              }
            }""")
        midoOps should contain (midonet.Update(networkWithUpdatedDhcpHosts))
    }

    "UPDATE VIF port with fixed IPs" should "update security rules" in {
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(networkWithDhcp).future)
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFipsAndSgs).future)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(portWithChains).future)
        when(storage.get(classOf[Chain], inboundChainId))
            .thenReturn(Promise.successful(inboundChain).future)
        when(storage.get(classOf[Chain], outboundChainId))
            .thenReturn(Promise.successful(outboundChain).future)
        when(storage.get(classOf[IpAddrGroup], sgId1))
            .thenReturn(Promise.successful(ipAddrGroup1).future)
        when(storage.get(classOf[IpAddrGroup], sgId2))
            .thenReturn(Promise.successful(ipAddrGroup2).future)

        val midoOps: List[Operation[Message]] =
            translator.translate(neutron.Update(vifPortWithFipsAndSgs2))
                      .asInstanceOf[List[Operation[Message]]]

        val revFlowRuleOutbound = mRuleFromTxt(s"""
            action: ACCEPT
            chain_id { $outboundChainId }
            match_return_flow: true
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            action: ACCEPT
            chain_id { $inboundChainId }
            match_return_flow: true
            """)
        val ipSpoofProtectIpv4 = mRuleFromTxt(s"""
            action: DROP
            chain_id { $inboundChainId }
            dl_type: ${IPv4.ETHERTYPE.asInstanceOf[Short]}
            nw_src_ip: { $ipv4Subnet1 }
            nw_src_inv: true
            fragment_policy: ANY
            """)
        val macSpoofProtect = mRuleFromTxt(s"""
            action: DROP
            chain_id { $inboundChainId }
            dl_src: '$mac'
            inv_dl_src: true
            fragment_policy: ANY
            """)
        val jumpRuleIn1 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $inboundChainId }
            jump_to { $ipAddrGroup1InChainId }
            """)
        val jumpRuleIn2 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $inboundChainId }
            jump_to { $ipAddrGroup2InChainId }
            """)
        val jumpRuleOut1 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $outboundChainId }
            jump_to { $ipAddrGroup1OutChainId }
            """)
        val jumpRuleOut2 = mRuleFromTxt(s"""
            action: JUMP
            chain_id { $outboundChainId }
            jump_to { $ipAddrGroup2OutChainId }
            """)
        val dropNonArpIn = mRuleFromTxt(s"""
            action: DROP
            chain_id { $inboundChainId }
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)
        val dropNonArpOut = mRuleFromTxt(s"""
            action: DROP
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
                ip_address { $ipv4Addr }
                port_id { $portId }
            }
            inbound_chain_id { $ipAddrGroup2InChainId }
            outbound_chain_id { $ipAddrGroup2OutChainId }
            """)
        midoOps should contain (midonet.Update(ipAddrGrp1))
        midoOps should contain (midonet.Update(ipAddrGrp1))
    }

    "DELETE VIF port with fixed IPs" should "delete the MidoNet Port" in {
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(networkWithDhcp).future)
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFixedIps).future)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(portWithChains).future)
        when(storage.get(classOf[Chain], inboundChainId))
            .thenReturn(Promise.successful(inboundChain).future)
        when(storage.get(classOf[Chain], outboundChainId))
            .thenReturn(Promise.successful(outboundChain).future)

        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
    }

    "DELETE VIF port with fixed IPs" should "delete DHCP host entries and " +
    "chains." in {
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(networkWithDhcp).future)
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortWithFipsAndSgs).future)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(portWithChains).future)
        when(storage.get(classOf[Chain], inboundChainId))
            .thenReturn(Promise.successful(inboundChain).future)
        when(storage.get(classOf[Chain], outboundChainId))
            .thenReturn(Promise.successful(outboundChain).future)
        when(storage.get(classOf[IpAddrGroup], sgId1))
            .thenReturn(Promise.successful(ipAddrGroup1).future)
        when(storage.get(classOf[IpAddrGroup], sgId2))
            .thenReturn(Promise.successful(ipAddrGroup2).future)

        val midoOps = translator.translate(neutron.Delete(classOf[NeutronPort],
                                                          portId))
        val networkWithNoDhcpHosts = mNetworkFromTxt(s"""
            $midoNetworkBase
            dhcp_subnets {
              subnet_address {
                version: V4
                address: "$subnetAddr1"
                prefix_length: 8
              }
            }
            dhcp_subnets {
              subnet_address {
                version: V6
                address: "$ipv6AddrTxt"
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

@RunWith(classOf[JUnitRunner])
class DhcpPortTranslationTest extends PortTranslatorTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortUp).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(midoNetwork).future)
    }

    private val dhcpPortUp = nPortFromTxt(portBaseUp + """
        device_owner: DHCP
        """)

    "DHCP port CREATE" should "create a normal Network port" in {
        val midoOps = translator.translate(neutron.Create(dhcpPortUp))
        midoOps should contain only midonet.Create(midoPortUp)
    }

    // TODO test that DHCP port CREATE adds a route to meta data service.
    // TODO test that DHCP port CREATE adds DHCP IP Neutron data.

    "DHCP port UPDATE" should "update port admin state" in {
        val dhcpPortDown = dhcpPortUp.toBuilder().setAdminStateUp(false).build
        val midoOps = translator.translate(neutron.Update(dhcpPortDown))

        midoOps should contain (midonet.Update(midoPortDown))
    }

    "DHCP port  DELETE" should "delete the MidoNet Port" in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(dhcpPortUp).future)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpPortTranslationTest extends PortTranslatorTest {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(midoPortUp).future)
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
            .thenReturn(Promise.successful(midoPortUp).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(midoNetwork).future)
    }

    private val routerIfPortUp = nPortFromTxt(portBaseUp + """
        device_owner: ROUTER_INTF
        """)

    "Router interface port CREATE" should "create a normal Network port" in {
        val midoOps = translator.translate(neutron.Create(routerIfPortUp))
        midoOps should contain only midonet.Create(midoPortUp)
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
            .thenReturn(Promise.successful(midoPortUp).future)
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(midoNetwork).future)
    }

    private val routerGatewayPort = nPortFromTxt(portBaseUp + """
        device_owner: ROUTER_GW
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