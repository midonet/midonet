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

package org.midonet.midolman.openstack.metadata

import akka.actor.ActorSystem
import java.util.UUID

import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Matchers.{eq => mockEq}
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfter
import org.scalatest.FeatureSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.PacketWorkflow.Drop
import org.midonet.midolman.PacketWorkflow.NoOp
import org.midonet.midolman.PacketWorkflow.ShortDrop
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.FlowMatch
import org.midonet.packets.ARP
import org.midonet.packets.IPv4
import org.midonet.packets.IPv6
import org.midonet.packets.IPv4Addr
import org.midonet.packets.MAC
import org.midonet.packets.TCP
import org.midonet.packets.UDP
import org.midonet.util.logging.Logger


@RunWith(classOf[JUnitRunner])
class MetadataServiceWorkflowTest extends FeatureSpecLike
        with Matchers
        with BeforeAndAfter
        with MockitoSugar {

    implicit val as = mock[ActorSystem]

    val workflow = new TestableWorkflow
    val vmPortNumber = 1234
    val vmMappedIpAddress = AddressManager dpPortToRemoteAddress vmPortNumber
    val vmMacAddress = "a6:5e:60:ac:c0:00"
    val metadataPortNumber = 999
    val metadataMacAddress = "e2:24:e2:87:9f:c6"
    val vmIpAddress = "192.0.2.1"

    val mdInfo = mock[ProxyInfo]
    when(mdInfo.dpPortNo).thenReturn(metadataPortNumber)

    before {
        MetadataServiceWorkflow.mdInfo = mdInfo
    }

    after {
        MetadataServiceWorkflow.mdInfo = null
    }

    def mockVmInfo = {
        val info = mock[InstanceInfo]

        when(info.mac).thenReturn(vmMacAddress)
        when(info.address).thenReturn(vmIpAddress)
        info
    }

    def mockPacketContext = {
        val context = mock[PacketContext]
        // Logger is not mockable as it's final
        val log = Logger(NOPLogger.NOP_LOGGER)
        val fmatch = mock[FlowMatch]

        when(context.wcmatch).thenReturn(fmatch)
        when(context.log).thenReturn(log)
        context
    }

    def mockForIngress = {
        val context = mockPacketContext
        val fmatch = context.wcmatch

        when(fmatch.getEtherType).thenReturn(IPv4.ETHERTYPE)
        when(fmatch.getNetworkDstIP).thenReturn(
            IPv4Addr(MetadataApi.Address))
        when(fmatch.getNetworkProto).thenReturn(TCP.PROTOCOL_NUMBER)
        when(fmatch.getDstPort).thenReturn(MetadataApi.Port)
        when(fmatch.getInputPortNumber).thenReturn(vmPortNumber)
        context
    }

    feature("MetadataServiceWorkflow ingress") {
        scenario("disabled") {
            val context = mockForIngress

            MetadataServiceWorkflow.mdInfo = null
            workflow handleMetadataIngress context shouldBe null
        }

        scenario("non ipv4") {
            val context = mockForIngress
            val fmatch = context.wcmatch

            when(fmatch.getEtherType).thenReturn(IPv6.ETHERTYPE)
            workflow handleMetadataIngress context shouldBe null
        }

        scenario("non metadata address") {
            val context = mockForIngress
            val fmatch = context.wcmatch

            when(fmatch.getNetworkDstIP).thenReturn(IPv4Addr(vmIpAddress))
            workflow handleMetadataIngress context shouldBe null
        }

        scenario("non metadata port") {
            val context = mockForIngress
            val fmatch = context.wcmatch

            when(fmatch.getDstPort).thenReturn(8080)
            workflow handleMetadataIngress context shouldBe null
        }

        scenario("non tcp") {
            val context = mockForIngress
            val fmatch = context.wcmatch

            when(fmatch.getNetworkProto).thenReturn(UDP.PROTOCOL_NUMBER)
            workflow handleMetadataIngress context shouldBe null
        }

        scenario("match") {
            val context = mockForIngress

            workflow handleMetadataIngress context shouldBe
                AddVirtualWildcardFlow
            verify(context).addVirtualAction(
                mockEq(output(metadataPortNumber)))
        }
    }

    def mockForEgress = {
        val context = mockPacketContext
        val fmatch = context.wcmatch

        when(fmatch.getInputPortNumber).thenReturn(metadataPortNumber)
        context
    }

    def mockForEgressArp = {
        val context = mockForEgress
        val fmatch = context.wcmatch
        val arpReq = makeArpRequest(MAC fromString metadataMacAddress,
                                    IPv4Addr(MetadataApi.Address),
                                    IPv4Addr(vmMappedIpAddress))

        when(fmatch.getEtherType).thenReturn(ARP.ETHERTYPE)
        when(fmatch.getNetworkProto).thenReturn(ARP.OP_REQUEST.toByte)
        when(fmatch.getNetworkDstIP).thenReturn(IPv4Addr(vmMappedIpAddress))
        when(context.ethernet).thenReturn(arpReq)
        context
    }

    def makeArpRequest(sha: MAC, spa: IPv4Addr, tpa: IPv4Addr) = {
        import org.midonet.packets.util.PacketBuilder._

        (eth mac sha -> eth_bcast) <<
        (arp.req mac sha -> eth_zero ip spa --> tpa)
    }

    def mockForEgressTcp = {
        val context = mockForEgress
        val fmatch = context.wcmatch

        when(fmatch.getEtherType).thenReturn(IPv4.ETHERTYPE)
        when(fmatch.getNetworkSrcIP).thenReturn(IPv4Addr(MetadataApi.Address))
        when(fmatch.getNetworkDstIP).thenReturn(IPv4Addr(vmMappedIpAddress))
        when(fmatch.getNetworkProto).thenReturn(TCP.PROTOCOL_NUMBER)
        when(fmatch.getSrcPort).thenReturn(Proxy.Port)
        context
    }

    feature("MetadataServiceWorkflow egress") {
        scenario("disabled") {
            val context = mockForEgressArp

            MetadataServiceWorkflow.mdInfo = null
            workflow handleMetadataEgress context shouldBe null
        }

        scenario("non metadata port") {
            val context = mockForEgressArp
            val fmatch = context.wcmatch

            when(fmatch.getInputPortNumber).thenReturn(vmPortNumber)
            workflow handleMetadataEgress context shouldBe null
        }

        scenario("no match") {
            val context = mockForEgressArp
            val fmatch = context.wcmatch

            when(fmatch.getEtherType).thenReturn(IPv6.ETHERTYPE)
            workflow handleMetadataEgress context shouldBe Drop
        }
    }

    feature("MetadataServiceWorkflow egress arp") {
        scenario("unknown tpa") {
            val context = mockForEgressArp

            workflow handleMetadataEgress context shouldBe ShortDrop
        }

        scenario("non request") {
            val context = mockForEgressArp
            val fmatch = context.wcmatch
            val portJId = UUID.randomUUID
            val info = mockVmInfo

            when(fmatch.getNetworkProto).thenReturn(ARP.OP_REPLY.toByte)
            InstanceInfoMap.put(vmMappedIpAddress, portJId, info)
            workflow handleMetadataEgress context shouldBe Drop
            InstanceInfoMap removeByPortId portJId
        }

        scenario("generate response") {
            val context = mockForEgressArp
            val portJId = UUID.randomUUID
            val info = mockVmInfo

            InstanceInfoMap.put(vmMappedIpAddress, portJId, info)
            workflow handleMetadataEgress context shouldBe NoOp
            InstanceInfoMap removeByPortId portJId
            verify(context).addGeneratedPhysicalPacket(
                mockEq(metadataPortNumber), any())
        }
    }

    feature("MetadataServiceWorkflow egress tcp") {
        scenario("unknown remote") {
            val context = mockForEgressTcp

            workflow handleMetadataEgress context shouldBe ShortDrop
        }

        scenario("non metadata src ip") {
            val context = mockForEgressTcp
            val fmatch = context.wcmatch
            val portJId = UUID.randomUUID
            val info = mockVmInfo

            when(fmatch.getNetworkSrcIP).thenReturn(IPv4Addr(vmIpAddress))
            InstanceInfoMap.put(vmMappedIpAddress, portJId, info)
            workflow handleMetadataEgress context shouldBe Drop
            InstanceInfoMap removeByPortId portJId
        }

        scenario("non metadata src port") {
            val context = mockForEgressTcp
            val fmatch = context.wcmatch
            val portJId = UUID.randomUUID
            val info = mockVmInfo

            when(fmatch.getSrcPort).thenReturn(8080)
            InstanceInfoMap.put(vmMappedIpAddress, portJId, info)
            workflow handleMetadataEgress context shouldBe Drop
            InstanceInfoMap removeByPortId portJId
        }

        scenario("non tcp") {
            val context = mockForEgressTcp
            val fmatch = context.wcmatch
            val portJId = UUID.randomUUID
            val info = mockVmInfo

            when(fmatch.getNetworkProto).thenReturn(UDP.PROTOCOL_NUMBER)
            InstanceInfoMap.put(vmMappedIpAddress, portJId, info)
            workflow handleMetadataEgress context shouldBe Drop
            InstanceInfoMap removeByPortId portJId
        }

        scenario("match") {
            val context = mockForEgressTcp
            val portJId = UUID.randomUUID
            val info = mockVmInfo

            InstanceInfoMap.put(vmMappedIpAddress, portJId, info)
            workflow handleMetadataEgress context shouldBe
                AddVirtualWildcardFlow
            InstanceInfoMap removeByPortId portJId
            verify(context).addVirtualAction(mockEq(output(vmPortNumber)))
        }
    }
}

class TestableWorkflow extends MetadataServiceWorkflow
