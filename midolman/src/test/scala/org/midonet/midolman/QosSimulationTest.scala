/*
 * Copyright 2014-2015 Midokura SARL
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
package org.midonet.midolman

import java.util.UUID

import scala.collection.JavaConversions._

import com.typesafe.config.{Config, ConfigFactory}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.{MidolmanSpec, TestDatapathState}
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.{FlowActionSetKey, FlowKey, FlowKeyIPv4, FlowKeyTunnel}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._


class QosSimulationBaseTest extends MidolmanSpec {

    var vt: VirtualTopology =_

    var qosPolicy1: UUID = _
    var dscpRule1: UUID = _

    var port1OnHost1WithQos: UUID = _
    var port1OnHost2: UUID = _

    var bridge: UUID = _
    var bridgeDevice: Bridge = _

    val host1Ip = IPv4Addr("192.168.100.1")
    val host2Ip = IPv4Addr("192.168.100.2")

    val port2OnHost1Mac = "0a:fe:88:70:33:ab"

    var translator: FlowTranslator = _
    var datapath: TestDatapathState = _

    var host1: UUID = hostId
    var host2: UUID = _

    var tunnelZone: UUID = _

    override def beforeTest(): Unit ={
        vt = injector.getInstance(classOf[VirtualTopology])

        tunnelZone = greTunnelZone("default")

        datapath = new TestDatapathState
        translator = new TestFlowTranslator(datapath)

        bridge = newBridge("bridge")

        qosPolicy1 = newQosPolicy()
        dscpRule1 = newQosDscpRule(qosPolicy1, dscpMark = 22)

        port1OnHost1WithQos = newBridgePort(bridge,
            qosPolicyId = Some(qosPolicy1))

        materializePort(port1OnHost1WithQos, host1, "port6")

        List(host1).zip(List(host1Ip)).foreach{
            case (host, ip) =>
                addTunnelZoneMember(tunnelZone, host, ip)
        }

        fetchPorts(port1OnHost1WithQos)

        bridgeDevice = fetchDevice[Bridge](bridge)

    }

    protected def verifyDSCP(ctx: PacketContext, dscpMark: Int,
                           requireSetKeyAction: Boolean = true,
                           requireTunnelAction: Boolean = true) : Unit = {
        ctx.calculateActionsFromMatchDiff()

        ctx.wcmatch.isSeen(FlowMatch.Field.NetworkTOS) shouldBe true
        // Must shift away the ECN field
        ctx.wcmatch.getNetworkTOS >> 2 shouldBe dscpMark

        if (requireSetKeyAction) {
            val flowActions: Set[FlowKey] = ctx.virtualFlowActions filter
              (_.isInstanceOf[FlowActionSetKey]) filter
              (_.asInstanceOf[FlowActionSetKey].getFlowKey.isInstanceOf[FlowKeyIPv4]) map
              (_.asInstanceOf[FlowActionSetKey].getFlowKey) toSet

            flowActions should not be empty

            val tosActions = flowActions filter (key =>
                (key.asInstanceOf[FlowKeyIPv4].ipv4_tos >> 2) == dscpMark.toByte)
            tosActions should not be empty
        }

        if (requireTunnelAction) {
            ctx.wcmatch.isSeen(FlowMatch.Field.TunnelTOS) shouldBe true
            ctx.wcmatch.getTunnelTOS >> 2 shouldBe dscpMark
            ctx.wcmatch.getTunnelTOS & 0x3 shouldBe 0

            val flowActions: Set[FlowKey] = ctx.flowActions filter
              (_.isInstanceOf[FlowActionSetKey]) filter
              (_.asInstanceOf[FlowActionSetKey].getFlowKey.isInstanceOf[FlowKeyTunnel]) map
              (_.asInstanceOf[FlowActionSetKey].getFlowKey) toSet

            flowActions should not be empty

            val tosActions = flowActions filter (key =>
                (key.asInstanceOf[FlowKeyTunnel].ipv4_tos >> 2) == dscpMark.toByte)

            tosActions should not be empty
        }
    }

    protected def verifyNoDSCP(ctx: PacketContext) : Unit = {
        ctx.calculateActionsFromMatchDiff()
        ctx.wcmatch.isSeen(FlowMatch.Field.NetworkTOS) shouldBe false
        ctx.wcmatch.isSeen(FlowMatch.Field.TunnelTOS) shouldBe false
        ctx.virtualFlowActions filter
          (_.isInstanceOf[FlowActionSetKey]) filter
          (_.asInstanceOf[FlowActionSetKey].getFlowKey.isInstanceOf[FlowKeyIPv4]) map
          (_.asInstanceOf[FlowActionSetKey].getFlowKey) shouldBe empty
        verifyNoDSCPOnTunnel(ctx)
    }

    protected def verifyNoDSCPOnTunnel(ctx: PacketContext) : Unit = {
        ctx.wcmatch.isSeen(FlowMatch.Field.TunnelTOS) shouldBe false
        ctx.wcmatch.getTunnelTOS >> 2 shouldBe 0
    }
}

@RunWith(classOf[JUnitRunner])
class QosDefaultSimulationTest extends QosSimulationBaseTest {

    scenario("dscp mark is added to IPv4 packets") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
          { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 0 } <<
          { udp src 10 dst 11 } << payload("My UDP packet")

        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        val (result, ctxOut) = simulate(ctx)

        verifyDSCP(ctxOut, 22, requireTunnelAction = false)
    }

    scenario("dscp mark is added to IPv4 packets with ECN set") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
          { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 2 } <<
          { udp src 10 dst 11 } << payload("My UDP packet")

        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        // Set the TOS field as 2 to simulate a phony ECN value
        // set this here as the created context currently clears the TOS field,
        // even if diffServ is set in the ethernet packet (bug: MI-1889)
        ctx.origMatch.setNetworkTOS(2)

        val (result, ctxOut) = simulate(ctx)

        verifyDSCP(ctxOut, 22, requireTunnelAction = false)

        // The last two bits are the leftover ECN
        ctxOut.wcmatch.getNetworkTOS & 0x03 shouldBe 2
    }

    scenario("dscp mark is kept on IPv4 packets when ToS is same as DSCP mark") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
          { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 88 } <<
          { udp src 10 dst 11 } << payload("My UDP packet")
        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        // 88 is the dscp mark shifted left twice
        // set this here as the created context currently clears the TOS field,
        // even if diffServ is set in the ethernet packet (bug: MI-1889)
        ctx.wcmatch.setNetworkTOS(88)

        val (result, ctxOut) = simulate(ctx)

        verifyDSCP(ctxOut, 22,
                   requireSetKeyAction = false,
                   requireTunnelAction = false)
    }

    scenario("dscp mark is not set on the tunneled packet by default") {
        Given("A second host with a port bound")
        host2 = newHost("host2", UUID.randomUUID(), Set(tunnelZone))
        addTunnelZoneMember(tunnelZone, host2, host2Ip)
        datapath.peerTunnels += (host2 -> Route(
            host2Ip.addr, host1Ip.addr, output(datapath.vxlanPortNumber)))
        port1OnHost2 = newBridgePort(bridge)
        materializePort(port1OnHost2, host2, "port7")
        fetchPorts(port1OnHost2)

        When("Sending a packet from a port with a QoS policy")
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
          { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 2 } <<
          { udp src 10 dst 11 } << payload("My UDP packet")

        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        // Set the TOS field as 2 to simulate a phony ECN value
        // and check that the tunnel TOS field sets them to 0 (bug: MI-1889)
        ctx.origMatch.setNetworkTOS(2)
        val (result, ctxOut) = simulate(ctx)

        Then("The TunnelFlowKey does not contain the DSCP mark")
        translator.translateActions(ctxOut)
        verifyDSCP(ctxOut, 22, requireTunnelAction = false)
        verifyNoDSCPOnTunnel(ctxOut)
    }

    scenario("dscp mark is not added to non-IPv4 packets") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst eth_bcast } <<
          { arp.req.mac(srcMac -> eth_bcast)
            .ip("10.10.10.11" --> "10.11.11.10") }
        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        val (result, ctxOut) = simulate(ctx)

        verifyNoDSCP(ctxOut)
    }
}

@RunWith(classOf[JUnitRunner])
class QosOnTunnelHeaderSimulationTest extends QosSimulationBaseTest {

    override protected def fillConfig(config: Config) : Config = {
        val defaults = """agent.datapath.set_tos_on_tunnel_header : true"""

        config.withFallback(ConfigFactory.parseString(defaults))
    }

    scenario("dscp mark is set on the tunneled packet if configured to do so") {
        Given("A second host with a port bound")
        host2 = newHost("host2", UUID.randomUUID(), Set(tunnelZone))
        addTunnelZoneMember(tunnelZone, host2, host2Ip)
        datapath.peerTunnels += (host2 -> Route(
            host2Ip.addr, host1Ip.addr, output(datapath.vxlanPortNumber)))
        port1OnHost2 = newBridgePort(bridge)
        materializePort(port1OnHost2, host2, "port7")
        fetchPorts(port1OnHost2)

        When("Sending a packet from a port with a QoS policy")
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
          { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 2 } <<
          { udp src 10 dst 11 } << payload("My UDP packet")

        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        // Set the TOS field as 2 to simulate a phony ECN value
        // and check that the tunnel TOS field sets them to 0 (bug: MI-1889)
        ctx.origMatch.setNetworkTOS(2)
        val (result, ctxOut) = simulate(ctx)

        Then("The translated actions contain a tunnel flow key with the same DSCP mark")
        translator.translateActions(ctxOut)
        verifyDSCP(ctxOut, 22)
    }
}
