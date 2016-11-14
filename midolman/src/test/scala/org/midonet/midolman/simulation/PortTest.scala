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

import org.midonet.cluster.state.PortStateStorage.PortActive
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, ErrorDrop}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.rules.{LiteralRule, Nat64Rule, NatTarget, Rule}
import org.midonet.midolman.simulation.Simulator.Nat64Action
import org.midonet.midolman.state.{ArpRequestBroker, HappyGoLuckyLeaser}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, IPv4Addr}
import org.midonet.util.UnixClock

@RunWith(classOf[JUnitRunner])
class PortTest extends MidolmanSpec with TopologyBuilder {

    private var arpBroker: ArpRequestBroker = _

    protected override def beforeTest(): Unit = {
        val clazz = Class.forName(classOf[MidolmanConfig].getName + "$")
        val field = clazz.getDeclaredField("fip64Vxlan")
        field.setAccessible(true)
        field.setBoolean(clazz.getField("MODULE$").get(null), true)
        arpBroker = new ArpRequestBroker(config, simBackChannel, UnixClock.mock())
    }

    private def activePort(rules: Rule*): Port = {
        Port(createRouterPort(routerId = Some(UUID.randomUUID()),
                              adminStateUp = true),
             PortActive(UUID.randomUUID(), Some(1L)),
             Collections.emptyList(),
             Collections.emptyList(),
             fipNatRules = rules.asJava)
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

    feature("Router port handles NAT64 packets") {
        scenario("Port with an empty NAT64") {
            Given("A NAT64 rule")
            val rule = new Nat64Rule

            And("A router port")
            val port = activePort(rule)

            And("A packet and context")
            val context = contextOf(packet("1.2.3.4", "5.6.7.8"))

            When("Simulating the port egress")
            val result = port.egress(context)

            Then("The packet should be dropped")
            result shouldBe ErrorDrop
        }


        scenario("Port with matching NAT64") {
            Given("A NAT64 rule")
            val rule = new Nat64Rule()
            rule.natPool = new NatTarget("5.6.7.1", "5.6.7.254", 0, 0)

            And("A router port")
            val port = activePort(rule)

            And("A packet and context")
            val context = contextOf(packet("1.2.3.4", "5.6.7.8"))

            When("Simulating the port egress")
            val result = port.egress(context)

            Then("The result should add a flow")
            result shouldBe AddVirtualWildcardFlow
            context.virtualFlowActions should contain only Nat64Action(null, 1L)
        }

        scenario("Port with other rule") {
            Given("A non-NAT64 rule")
            val rule = new LiteralRule()

            And("A router port")
            val port = activePort(rule)

            And("A packet and context")
            val context = contextOf(packet("1.2.3.4", "5.6.7.8"))

            When("Simulating the port egress")
            val result = port.egress(context)

            Then("The packet should be dropped")
            result shouldBe ErrorDrop
        }

        scenario("Port with multiple rules, one NAT64") {
            Given("A NAT64 rule")
            val natRule = new Nat64Rule()
            natRule.natPool = new NatTarget("5.6.7.1", "5.6.7.254", 0, 0)

            And("A non-NAT64 rule")
            val literalRule = new LiteralRule()

            And("A router port")
            val port = activePort(literalRule, natRule)

            And("A packet and context")
            val context = contextOf(packet("1.2.3.4", "5.6.7.8"))

            When("Simulating the port egress")
            val result = port.egress(context)

            Then("The result should add a flow")
            result shouldBe AddVirtualWildcardFlow
            context.virtualFlowActions should contain only Nat64Action(null, 1L)
        }

        scenario("Port with multiple NAT64 rules") {
            Given("Two NAT64 rules")
            val natRule1 = new Nat64Rule()
            natRule1.natPool = new NatTarget("5.6.6.1", "5.6.6.254", 0, 0)
            val natRule2 = new Nat64Rule()
            natRule2.natPool = new NatTarget("5.6.7.1", "5.6.7.254", 0, 0)

            And("A router port")
            val port = activePort(natRule1, natRule2)

            And("A packet and context")
            val context = contextOf(packet("1.2.3.4", "5.6.7.8"))

            When("Simulating the port egress")
            val result = port.egress(context)

            Then("The result should add a flow")
            result shouldBe AddVirtualWildcardFlow
            context.virtualFlowActions should contain only Nat64Action(null, 1L)
        }

        scenario("Non-IPv4 packet") {
            Given("A NAT64 rule")
            val rule = new Nat64Rule()
            rule.natPool = new NatTarget("5.6.7.1", "5.6.7.254", 0, 0)

            And("A router port")
            val port = activePort(rule)

            And("A packet and context")
            val frame =
                {
                    eth addr "01:02:03:04:05:06" -> "01:02:03:04:05:07"
                } << {
                    ip6.src("2001::1").dst("2001::2")
                } << {
                    udp ports 1000 ---> 1001
                } << payload(UUID.randomUUID().toString)
            val context = contextOf(frame)

            When("Simulating the port egress")
            val result = port.egress(context)

            Then("The packet should be dropped")
            result shouldBe ErrorDrop
        }
    }

}
