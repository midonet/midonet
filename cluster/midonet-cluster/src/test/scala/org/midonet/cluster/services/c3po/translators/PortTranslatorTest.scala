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

import scala.collection.JavaConverters._

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.{MatchResult, Matcher}

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Topology.{Chain, Port, Rule}
import org.midonet.cluster.services.c3po.C3POStorageManager.{OpType, Operation}
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.midolman.state.MacPortMap
import org.midonet.packets.{ARP, IPv4, IPv6, MAC}

trait OpMatchers {
    /**
     * This attempts to match the expected Message against the actual one,
     * ignoring the "id" field when the expected message doesn't have the field
     * set. This is needed because often an ID of a model is assigned randomly.
     */
    class ContainsOp[M <: Message](expected: Operation)
    extends Matcher[List[Operation]] {
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

        private def matchesOp(expected: Operation, actual: Operation) = {
            if (expected.opType == actual.opType) {
                expected match {
                    case midonet.Create(m) =>
                        matchesModuloId(m,
                                actual.asInstanceOf[midonet.Create[M]].model)
                    case midonet.Update(m, _) =>
                        matchesModuloId(m,
                                actual.asInstanceOf[midonet.Update[M]].model)
                    case midonet.Delete(clazz, id) => expected == actual
                }
            } else false
        }

        def apply(left: List[Operation]) =
            MatchResult(left.exists { matchesOp(expected, _) },
                        s"$left\n\ndoes not contain a matching operation\n\n" +
                        s"$expected",
                        s"$left\n\ncontains a matching operation\n\n$expected")
    }

    def containOp[M <: Message](expected: Operation) =
        new ContainsOp[M](expected)
}

/* A common base class for testing NeutronPort CRUD translation. */
class PortTranslatorTest extends TranslatorTestBase with ChainManager
                                                    with OpMatchers {
    protected var translator: PortTranslator = _

    protected val portId = randomUuidProto
    protected val portJUuid = UUIDUtil.fromProto(portId)
    protected val networkId = randomUuidProto
    protected val tenantId = "neutron tenant"
    protected val mac = "00:11:22:33:44:55"

    protected val portWithPeerId = randomUuidProto
    protected val peerRouterPortId = randomUuidProto

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

    val nNetworkBase = nNetworkFromTxt(s"""
        id { $networkId }
         """)

    val midoIpv4Dhcp = s"""
            id { $nIpv4Subnet1Id }
            network_id { $networkId }
            subnet_address { $ipv4Subnet1 }
            router_gw_port_id { $peerRouterPortId }
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

    protected def macEntryPath(nwId: UUID, mac: String, portId: UUID) = {
        val entry = MacPortMap.encodePersistentPath(
            MAC.fromString(mac), UUIDUtil.fromProto(portId))
        pathBldr.getBridgeMacPortEntryPath(UUIDUtil.fromProto(networkId),
                                           Bridge.UNTAGGED_VLAN_ID, entry)
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
}

/**
 * Tests the Neutron VIF Port model Create translation.
 */
@RunWith(classOf[JUnitRunner])
class VifPortCreateTranslationTest extends VifPortTranslationTest {
    before {
        initMockStorage()
        translator = new PortTranslator(storage, pathBldr)

        bind(networkId, nNetworkBase)
        bind(nIpv4Subnet1Id, nIpv4Subnet1)
        bind(nIpv6Subnet1Id, nIpv6Subnet1)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bind(nIpv6Subnet1Id, mIpv6Dhcp)
        bind(sgId1, ipAddrGroup1)
        bind(sgId2, ipAddrGroup2)
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
            }
            mac_address: "01:02:03:0a:0b:0c"
          }
          allowed_address_pairs {
            ip_address {
              version: V4
              address: '2.3.2.3'
            }
            mac_address: "0a:0b:0b:0a:0b:0c"
          }
          """)

        val jumpRule = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_to { $spoofChainId }
            }
            """)

        val fixedIp = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: RETURN
            dl_src: "$mac"
            chain_id: { $spoofChainId }
            nw_src_ip {
              version: V4
              address: "6.6.6.6"
              prefix_length: 32
            }
            fragment_policy: ANY
            """)

        val addrPairOne = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: RETURN
            dl_src: "0a:0b:0b:0a:0b:0c"
            chain_id: { $spoofChainId }
            nw_src_ip {
              version: V4
              address: "2.3.2.3"
              prefix_length: 32
            }
            fragment_policy: ANY
            """)

        val addrPairTwo = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: RETURN
            dl_src: "01:02:03:0a:0b:0c"
            chain_id: { $spoofChainId }
            nw_src_ip {
              version: V4
              address: '1.2.1.2'
              prefix_length: 32
            }
            fragment_policy: ANY
            """)

        val dropAll = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: DROP
            fragment_policy: ANY
            """)

        val arpRule = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: RETURN
            dl_type: ${ARP.ETHERTYPE}
            fragment_policy: ANY
            """)

        val dhcpRule = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $spoofChainId }
            action: RETURN
            tp_src: {
              start: 68
              end: 68
            }
            tp_dst: {
              start: 67
              end: 67
            }
            fragment_policy: ANY
            """)

        val midoOps = translator.translate(neutron.Create(vifPortWithAllowedAddressPairs))

        // For the outbound chain
        midoOps should containOp[Message] (midonet.Create(jumpRule))

        // For the Anti Spoof Chain
        midoOps should containOp[Message] (midonet.Create(arpRule))
        midoOps should containOp[Message] (midonet.Create(dhcpRule))
        midoOps should containOp[Message] (midonet.Create(fixedIp))
        midoOps should containOp[Message] (midonet.Create(addrPairOne))
        midoOps should containOp[Message] (midonet.Create(addrPairTwo))
        midoOps should containOp[Message] (midonet.Create(dropAll))
    }

    "Fixed IPs for a new VIF port" should "add hosts to DHCPs" in {
        val midoOps = translator.translate(neutron.Create(vifPortWithFixedIps))

        midoOps should contain (midonet.Update(mIpv4DhcpWithHostAdded))
        midoOps should contain (midonet.Update(mIpv6DhcpWithHostAdded))
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

        val midoOps = translator.translate(neutron.Create(vifPortNoPortSec))

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
        antiSpoofChain.getName shouldBe s"Anti Spoof Chain"
        antiSpoofChain.getRuleIdsList.size shouldBe 0
    }

    "A created VIF port" should "have security bindings" in {

        val midoOps: List[Operation] =
            translator.translate(neutron.Create(vifPortWithFipsAndSgs))
                      .asInstanceOf[List[Operation]]

        midoOps should contain (midonet.Create(mPortWithChains))

        // Security bindings.
        val revFlowRuleOutbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            match_return_flow: true
            chain_id { $outboundChainId }
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            match_return_flow: true
            chain_id { $inboundChainId }
            """)

        val antiSpoofJumpRule = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_to { $spoofChainId }
            }
            """)

        val jumpRuleIn1 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $inboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup1InChainId }
            }
            """)

        val jumpRuleIn2 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $inboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup2InChainId }
            }
            """)

        val jumpRuleOut1 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $outboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup1OutChainId }
            }
            """)

        val jumpRuleOut2 = mRuleFromTxt(s"""
            type: JUMP_RULE
            action: JUMP
            chain_id { $outboundChainId }
            jump_rule_data {
              jump_to { $ipAddrGroup2OutChainId }
            }
            """)

        val dropNonArpIn = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $inboundChainId }
            action: DROP
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        val dropNonArpOut = mRuleFromTxt(s"""
            type: LITERAL_RULE
            chain_id { $outboundChainId }
            action: DROP
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        val inChain = findChainOp(midoOps, OpType.Create, inboundChainId)
        inChain should not be null
        inChain.getName shouldBe s"OS_PORT_${portJUuid}_INBOUND"
        inChain.getRuleIdsList.size shouldBe 5

        val outChain= findChainOp(midoOps, OpType.Create, outboundChainId)
        outChain should not be null
        outChain.getName shouldBe s"OS_PORT_${portJUuid}_OUTBOUND"
        outChain.getRuleIdsList.size shouldBe 4

        val antiSpoofChain = findChainOp(midoOps, OpType.Create, spoofChainId)
        antiSpoofChain should not be null
        antiSpoofChain.getName shouldBe s"Anti Spoof Chain"
        antiSpoofChain.getRuleIdsList.size shouldBe 5

        midoOps should containOp[Message] (midonet.Create(revFlowRuleOutbound))
        midoOps should containOp[Message] (midonet.Create(revFlowRuleInbound))
        midoOps should containOp[Message] (midonet.Create(antiSpoofJumpRule))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn2))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut2))
        midoOps should containOp[Message] (midonet.Create(dropNonArpIn))
        midoOps should containOp[Message] (midonet.Create(dropNonArpOut))
        midoOps should contain(
            midonet.CreateNode(macEntryPath(networkId, mac, portId), null))

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
        midoOps should contain (midonet.Update(ipAddrGrp1))
        midoOps should contain (midonet.Update(ipAddrGrp2))
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

    protected val hostId = randomUuidProto
    protected val hostName = "hostname"
    protected val interfaceName = "tap0"
    protected val vifPortWithBinding = nPortFromTxt(s"""
        $portBaseUp
        host_id: '$hostName'
        profile {
            interface_name: '$interfaceName'
        }
        """)

    protected val mPortWithBinding = mPortFromTxt(s"""
        $mPortWithChains
        host_id { $hostId }
        interface_name: '$interfaceName'
        """)

    before {
        initMockStorage()
        translator = new PortTranslator(storage, pathBldr)

        bind(inboundChainId, inboundChain)
        bind(outboundChainId, outboundChain)
        bind(spoofChainId, antiSpoofChain)

        bind(portId, mPortWithBinding)
        bind(portId, vifPortWithBinding)
    }

    "VIF port UPDATE with no change " should "NOT update binding" in {
        val midoOps = translator.translate(neutron.Update(vifPortWithBinding))
        midoOps.exists {
            case midonet.Update(obj: Port, _) => true
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
        translator = new PortTranslator(storage, pathBldr)

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
        val mIpv4DhcpWithHostUpdated = mDhcpFromTxt(s"""
              $mIpv4Dhcp
              hosts {
                mac: "$mac"
                ip_address { $updatedFixedIp }
              }
            """)
        midoOps should contain (midonet.Update(mIpv4DhcpWithHostUpdated))
    }

    val vifPortWithFipsAndSgs2 = nPortFromTxt(s"""
        $vifPortWithFixedIps2
        security_groups { $sgId1 }
        security_groups { $sgId2 }
        """)

    "UPDATE VIF port with fixed IPs" should "update security rules" in {
        bind(portId, vifPortWithFipsAndSgs)

        val midoOps: List[Operation] =
            translator.translate(neutron.Update(vifPortWithFipsAndSgs2))
                      .asInstanceOf[List[Operation]]

        val revFlowRuleOutbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            match_return_flow: true
            chain_id { $outboundChainId }
            """)
        val revFlowRuleInbound = mRuleFromTxt(s"""
            type: LITERAL_RULE
            action: ACCEPT
            match_return_flow: true
            chain_id { $inboundChainId }
            """)

        val jumpRuleIn1 = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup1InChainId }
            }
            """)

        val jumpRuleIn2 = mRuleFromTxt(s"""
            chain_id { $inboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup2InChainId }
            }
            """)

        val jumpRuleOut1 = mRuleFromTxt(s"""
            chain_id { $outboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup1OutChainId }
            }
            """)

        val jumpRuleOut2 = mRuleFromTxt(s"""
            chain_id { $outboundChainId }
            type: JUMP_RULE
            action: JUMP
            jump_rule_data {
              jump_to { $ipAddrGroup2OutChainId }
            }
            """)

        val dropNonArpIn = mRuleFromTxt(s"""
            action: DROP
            type: LITERAL_RULE
            chain_id { $inboundChainId }
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        val dropNonArpOut = mRuleFromTxt(s"""
            action: DROP
            type: LITERAL_RULE
            chain_id { $outboundChainId }
            dl_type: ${ARP.ETHERTYPE}
            inv_dl_type: true
            fragment_policy: ANY
            """)

        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule1))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule2))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule3))
        midoOps should contain (midonet.Delete(classOf[Rule], outChainRule1))
        midoOps should contain (midonet.Delete(classOf[Rule], antiSpoofChainRule))
        midoOps should containOp[Message] (midonet.Create(revFlowRuleOutbound))
        midoOps should containOp[Message] (midonet.Create(revFlowRuleInbound))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleIn2))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut1))
        midoOps should containOp[Message] (midonet.Create(jumpRuleOut2))
        midoOps should containOp[Message] (midonet.Create(dropNonArpIn))
        midoOps should containOp[Message] (midonet.Create(dropNonArpOut))

        val inChain = findChainOp(midoOps, OpType.Update, inboundChainId)
        inChain should not be null
        inChain.getName shouldBe s"OS_PORT_${portJUuid}_INBOUND"
        inChain.getRuleIdsList.size shouldBe 5

        val outChain= findChainOp(midoOps, OpType.Update, outboundChainId)
        outChain should not be null
        outChain.getName shouldBe s"OS_PORT_${portJUuid}_OUTBOUND"
        outChain.getRuleIdsList.size shouldBe 4

        val antiSpoofChain = findChainOp(midoOps, OpType.Update, spoofChainId)
        antiSpoofChain should not be null
        antiSpoofChain.getName shouldBe s"Anti Spoof Chain"
        antiSpoofChain.getRuleIdsList.size shouldBe 4

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
        midoOps should contain (midonet.Update(ipAddrGrp1))
        midoOps should contain (midonet.Update(ipAddrGrp2))
    }

    "DELETE VIF port with fixed IPs" should "delete the MidoNet Port" in {
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
        midoOps should contain (
            midonet.DeleteNode(macEntryPath(networkId, mac, portId)))
    }

    "DELETE VIF port with fixed IPs" should "delete DHCP host entries and " +
    "chains." in {
        bind(portId, vifPortWithFipsAndSgs)

        val midoOps = translator.translate(neutron.Delete(classOf[NeutronPort],
                                                          portId))

        midoOps should contain (midonet.Update(mIpv4Dhcp))
        midoOps should contain (midonet.Update(mIpv6Dhcp))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule1))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule2))
        midoOps should contain (midonet.Delete(classOf[Rule], inChainRule3))
        midoOps should contain (midonet.Delete(classOf[Rule], antiSpoofChainRule))
        midoOps should contain (midonet.Delete(classOf[Chain], inboundChainId))
        midoOps should contain (midonet.Delete(classOf[Chain], outboundChainId))
        midoOps should contain (midonet.Delete(classOf[Chain], spoofChainId))
        midoOps should contain (
            midonet.DeleteNode(macEntryPath(networkId, mac, portId)))

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
        midoOps should contain (midonet.Update(ipAddrGrp1))
        midoOps should contain (midonet.Update(ipAddrGrp2))
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
        translator = new PortTranslator(storage, pathBldr)

        bind(networkId, nNetworkBase)
        bind(networkId, mNetworkWithDhcpPort)
        bind(nIpv4Subnet1Id, nIpv4Subnet1WithGwIP)
        bind(nIpv6Subnet1Id, nIpv6Subnet1)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bind(nIpv6Subnet1Id, mIpv6Dhcp)
        bind(portWithPeerId, mPortWithRPortPeer)
        bind(peerRouterPortId, mRouterPort)
        bind(routerId, mRouterWithGwPort)
    }

    "DHCP port CREATE" should "configure DHCP" in {
        val midoOps: List[Operation] =
            translator.translate(neutron.Create(dhcpPort))
                      .asInstanceOf[List[Operation]]

        midoOps.size shouldBe 4
        midoOps.head shouldBe midonet.Create(mRouteFromTxt(s"""
            id { ${RouteManager.metadataServiceRouteId(peerRouterPortId)} }
            src_subnet { $ipv4Subnet1 }
            dst_subnet { ${IPSubnetUtil.toProto("169.254.169.254/32")} }
            next_hop: PORT
            next_hop_port_id { $peerRouterPortId }
            next_hop_gateway { $ipv4Addr1 }
            weight: 100
            """))

        // Order of DHCP updates is undefined.
        midoOps(1) shouldBe a [midonet.Update[_]]
        midoOps(2) shouldBe a [midonet.Update[_]]
        midoOps should contain(midonet.Update(mIpv4DhcpWithDhcpConfigured))

        midoOps(3) shouldBe midonet.Create(midoPortBaseUp)
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
        translator = new PortTranslator(storage, pathBldr)

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
    }

    "DHCP port UPDATE" should "update port admin state" in {
        val dhcpPortDown = dhcpPort.toBuilder.setAdminStateUp(false).build
        val midoOps = translator.translate(neutron.Update(dhcpPortDown))

        midoOps should contain only midonet.Update(midoPortBaseDown)
    }

    // TODO Add an assert that the fixed IPs haven't been changed.

    "DHCP port  DELETE" should "delete the MidoNet Port" in {
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain only(
            midonet.Delete(classOf[Port], portId),
            midonet.Update(mIpv4Dhcp),
            midonet.Update(mIpv6Dhcp))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpPortTranslationTest extends PortTranslatorTest {
    before {
        initMockStorage()
        translator = new PortTranslator(storage, pathBldr)

        bind(networkId, nNetworkBase)
        bind(portId, null, classOf[Port])
        bind(networkId, midoNetwork)
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
        bind(portId, fipPortUp)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps shouldBe empty
    }
}

class RouterInterfacePortTranslationTest extends PortTranslatorTest {
    protected val deviceId = java.util.UUID.randomUUID
    protected val routerId = UUIDUtil.toProto(deviceId)
    protected val routerIfPortIp = IPAddressUtil.toProto("127.0.0.2")
    protected val routerIfPortUp = nPortFromTxt(portBaseUp + s"""
        fixed_ips {
            ip_address { $routerIfPortIp }
            subnet_id { $nIpv4Subnet1Id }
        }
        device_owner: ROUTER_INTERFACE
        device_id: "$deviceId"
        """)
}

@RunWith(classOf[JUnitRunner])
class RouterInterfacePortCreateTranslationTest
        extends RouterInterfacePortTranslationTest {
    before {
        initMockStorage()
        translator = new PortTranslator(storage, pathBldr)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
    }

    "Router interface port CREATE" should "create a normal Network port" in {
        bind(networkId, nNetworkBase)
        bind(networkId, mNetworkWithIpv4Subnet)
        val midoOps = translator.translate(neutron.Create(routerIfPortUp))
        midoOps should contain only midonet.Create(midoPortBaseUp)
    }
}

@RunWith(classOf[JUnitRunner])
class RouterInterfacePortUpdateDeleteTranslationTest
        extends RouterInterfacePortTranslationTest {
    import org.midonet.cluster.services.c3po.translators.PortManager._
    before {
        initMockStorage()
        translator = new PortTranslator(storage, pathBldr)

        bind(networkId, nNetworkBase)
        bind(nIpv4Subnet1Id, mIpv4Dhcp)
        bind(portId, midoPortBaseUp)
        bind(networkId, midoNetwork)
    }

    "Router interface port UPDATE" should "NOT update Port" in {
        val midoOps = translator.translate(neutron.Update(routerIfPortUp))
        midoOps shouldBe empty
     }

    "Router interface port DELETE" should "delete both MidoNet Router " +
    "Interface Network Port and its peer Router Port" in {
        bind(portId, routerIfPortUp)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain only (
                midonet.Delete(classOf[Port], portId),
                midonet.Delete(classOf[Port], routerInterfacePortPeerId(portId))
                )
    }
}

@RunWith(classOf[JUnitRunner])
class RouterGatewayPortTranslationTest extends PortTranslatorTest {
    before {
        initMockStorage()
        translator = new PortTranslator(storage, pathBldr)

        bind(networkId, nNetworkBase)
        bind(portId, midoPortBaseUp)
        bind(networkId, midoNetwork)
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
        bind(portId, routerGatewayPort)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
    }
}