/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.simulation

import java.util.{Collections, UUID}

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Topology
import org.midonet.cluster.state.PortStateStorage.{PortActive, PortInactive}
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, ErrorDrop}
import org.midonet.midolman.rules.{LiteralRule, NatTarget, Rule}
import org.midonet.midolman.simulation.Simulator.Fip64Action
import org.midonet.midolman.state.{ArpRequestBroker, HappyGoLuckyLeaser}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, IPv4Addr, IPv4Subnet, IPv6Subnet}
import org.midonet.util.UnixClock

@RunWith(classOf[JUnitRunner])
class PortTest extends MidolmanSpec with TopologyBuilder {

    private var arpBroker: ArpRequestBroker = _

    protected override def beforeTest(): Unit = {
        arpBroker = new ArpRequestBroker(config, simBackChannel, UnixClock.mock())
    }

    private def activePort(rules: Rule*): Port = {
        Port(createRouterPort(routerId = Some(UUID.randomUUID()),
                              adminStateUp = true),
             PortActive(UUID.randomUUID(), Some(1L)),
             Collections.emptyList(),
             Collections.emptyList())
    }

    private def packet(src: String, dst: String): EthBuilder = {
        {
            eth addr "01:02:03:04:05:06" -> "01:02:03:04:05:07"
        } << {
            ip4 addr src --> dst
        } << {
            udp ports 1000 ---> 1001
        } <<
        payload(UUID.randomUUID().toString)
    }

    private def contextOf(frame: Ethernet): PacketContext = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(frame))
        val context = PacketContext.generated(-1, new Packet(frame, fmatch),
                                              fmatch, null, null,
                                              simBackChannel, arpBroker)
        context.initialize(NO_CONNTRACK, NO_NAT, HappyGoLuckyLeaser, NO_TRACE)
        context.prepareForSimulation()
        context
    }

    implicit private def toIpAddr(str: String): IPv4Addr = IPv4Addr(str)

    feature("Create router port") {
        scenario("MidoNet 5.2 port with IPv4 address") {
            Given("A port")
            val address = new IPv4Subnet("1.2.3.4", 16)
            val proto = Topology.Port.newBuilder()
                .setId(UUIDUtil.randomUuidProto)
                .setRouterId(UUIDUtil.randomUuidProto)
                .setPortAddress(address.getAddress.asProto)
                .addPortSubnet(new IPv4Subnet(address.toNetworkAddress,
                                              address.getPrefixLen).asProto)
                .build()

            When("Creating a simulation")
            val port = Port(proto, PortInactive,
                            Collections.emptyList(),
                            Collections.emptyList()).asInstanceOf[RouterPort]

            Then("The port should contain the correct addresses")
            port.id shouldBe proto.getId.asJava
            port.portAddresses should contain only address
            port.portAddress4 shouldBe address
            port.portAddress6 shouldBe null
        }

        scenario("MidoNet 5.2 port with IPv6 address") {
            Given("A port")
            val address = new IPv6Subnet("2001::1", 64)
            val proto = Topology.Port.newBuilder()
                .setId(UUIDUtil.randomUuidProto)
                .setRouterId(UUIDUtil.randomUuidProto)
                .setPortAddress(address.getAddress.asProto)
                .addPortSubnet(new IPv6Subnet(address.toNetworkAddress,
                                              address.getPrefixLen).asProto)
                .build()

            When("Creating a simulation")
            val port = Port(proto, PortInactive,
                            Collections.emptyList(),
                            Collections.emptyList()).asInstanceOf[RouterPort]

            Then("The port should contain the correct addresses")
            port.id shouldBe proto.getId.asJava
            port.portAddresses should contain only address
            port.portAddress4 shouldBe null
            port.portAddress6 shouldBe address
        }

        scenario("MidoNet 5.3 port with IPv4 address") {
            Given("A port")
            val address = new IPv4Subnet("1.2.3.4", 16)
            val proto = Topology.Port.newBuilder()
                .setId(UUIDUtil.randomUuidProto)
                .setRouterId(UUIDUtil.randomUuidProto)
                .setPortAddress(address.getAddress.asProto)
                .addPortSubnet(address.asProto)
                .build()

            When("Creating a simulation")
            val port = Port(proto, PortInactive,
                            Collections.emptyList(),
                            Collections.emptyList()).asInstanceOf[RouterPort]

            Then("The port should contain the correct addresses")
            port.id shouldBe proto.getId.asJava
            port.portAddresses should contain only address
            port.portAddress4 shouldBe address
            port.portAddress6 shouldBe null
        }

        scenario("MidoNet 5.3 port with IPv6 address") {
            Given("A port")
            val address = new IPv6Subnet("2001::1", 64)
            val proto = Topology.Port.newBuilder()
                .setId(UUIDUtil.randomUuidProto)
                .setRouterId(UUIDUtil.randomUuidProto)
                .setPortAddress(address.getAddress.asProto)
                .addPortSubnet(address.asProto)
                .build()

            When("Creating a simulation")
            val port = Port(proto, PortInactive,
                            Collections.emptyList(),
                            Collections.emptyList()).asInstanceOf[RouterPort]

            Then("The port should contain the correct addresses")
            port.id shouldBe proto.getId.asJava
            port.portAddresses should contain only address
            port.portAddress4 shouldBe null
            port.portAddress6 shouldBe address
        }

        scenario("MidoNet 5.3 port with IPv4 and IPv6 address") {
            Given("A port")
            val address4 = new IPv4Subnet("1.2.3.4", 16)
            val address6 = new IPv6Subnet("2001::1", 64)
            val proto = Topology.Port.newBuilder()
                .setId(UUIDUtil.randomUuidProto)
                .setRouterId(UUIDUtil.randomUuidProto)
                .addPortSubnet(address4.asProto)
                .addPortSubnet(address6.asProto)
                .build()

            When("Creating a simulation")
            val port = Port(proto, PortInactive,
                            Collections.emptyList(),
                            Collections.emptyList()).asInstanceOf[RouterPort]

            Then("The port should contain the correct addresses")
            port.id shouldBe proto.getId.asJava
            port.portAddresses should contain allOf(address4, address6)
            port.portAddress4 shouldBe address4
            port.portAddress6 shouldBe address6
        }

        scenario("MidoNet 5.3 port with multiple address") {
            Given("A port")
            val address4_1 = new IPv4Subnet("1.2.3.4", 16)
            val address4_2 = new IPv4Subnet("1.2.3.5", 16)
            val address6_1 = new IPv6Subnet("2001::1", 64)
            val address6_2 = new IPv6Subnet("2001::2", 64)
            val proto = Topology.Port.newBuilder()
                .setId(UUIDUtil.randomUuidProto)
                .setRouterId(UUIDUtil.randomUuidProto)
                .addPortSubnet(address4_1.asProto)
                .addPortSubnet(address6_1.asProto)
                .addPortSubnet(address4_2.asProto)
                .addPortSubnet(address6_2.asProto)
                .build()

            When("Creating a simulation")
            val port = Port(proto, PortInactive,
                            Collections.emptyList(),
                            Collections.emptyList()).asInstanceOf[RouterPort]

            Then("The port should contain the correct addresses")
            port.id shouldBe proto.getId.asJava
            port.portAddresses should contain allOf(address4_1, address6_1,
                address4_2, address6_2)
            port.portAddress4 shouldBe address4_1
            port.portAddress6 shouldBe address6_1
        }
    }

    feature("Router port handles NAT64 packets") {

        scenario("Port forwards for translation") {
            And("A router port")
            val port = activePort()

            And("A packet and context, marked for translation")
            val context = contextOf(packet("1.2.3.4", "5.6.7.8"))
            context.markForFip64()

            When("Simulating the port egress")
            val result = port.egress(context)

            Then("The result should add a flow")
            result shouldBe AddVirtualWildcardFlow
            context.virtualFlowActions should contain only Fip64Action(null, 1L)
        }

    }

}
