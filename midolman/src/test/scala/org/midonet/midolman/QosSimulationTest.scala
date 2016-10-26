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

import org.midonet.odp.FlowMatch

import scala.collection.JavaConversions._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowActionSetKey, FlowKey, FlowKeyIPv4}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._


@RunWith(classOf[JUnitRunner])
class QosSimulationTest extends MidolmanSpec {

    private var vt: VirtualTopology =_

    private var qosPolicy1: UUID = _
    private var dscpRule1: UUID = _

    private var port1OnHost1WithQos: UUID = _

    private var bridge: UUID = _
    private var bridgeDevice: Bridge = _


    val host1Ip = IPv4Addr("192.168.100.1")

    val port2OnHost1Mac = "0a:fe:88:70:33:ab"

    override def beforeTest(): Unit ={

        vt = injector.getInstance(classOf[VirtualTopology])

        val tunnelZone = greTunnelZone("default")

        val host1 = hostId

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

    scenario("dscp mark is added to IPv4 packets") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
          { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 0 } <<
          { udp src 10 dst 11 } << payload("My UDP packet")

        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        val (result, ctxOut) = simulate(ctx)

        verifyDSCP(ctxOut, 22)
    }

    scenario("dscp mark is kept on IPv4 packets when ToS is same as DSCP mark") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
          { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 22 } <<
          { udp src 10 dst 11 } << payload("My UDP packet")
        val ctx = packetContextFor(ethPkt, port1OnHost1WithQos)

        val (result, ctxOut) = simulate(ctx)

        verifyDSCP(ctxOut, 22, requireSetKeyAction = false)
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

    private def verifyDSCP(ctx: PacketContext, dscpMark: Int,
                           requireSetKeyAction: Boolean = true) : Unit = {
        ctx.calculateActionsFromMatchDiff()

        ctx.wcmatch.isSeen(FlowMatch.Field.NetworkTOS) shouldBe true
        ctx.wcmatch.getNetworkTOS shouldBe dscpMark

        if (requireSetKeyAction) {
            val flowActions: Set[FlowKey] = ctx.virtualFlowActions filter
              (_.isInstanceOf[FlowActionSetKey]) filter
              (_.asInstanceOf[FlowActionSetKey].getFlowKey.isInstanceOf[FlowKeyIPv4]) map
              (_.asInstanceOf[FlowActionSetKey].getFlowKey) toSet

            flowActions should not be empty

            val tosActions = flowActions filter
              (_.asInstanceOf[FlowKeyIPv4].ipv4_tos == dscpMark.toByte)

            tosActions should not be empty
        }
    }

    private def verifyNoDSCP(ctx: PacketContext) : Unit = {
        ctx.calculateActionsFromMatchDiff()
        ctx.wcmatch.isSeen(FlowMatch.Field.NetworkTOS) shouldBe false
        ctx.virtualFlowActions filter
          (_.isInstanceOf[FlowActionSetKey]) filter
          (_.asInstanceOf[FlowActionSetKey].getFlowKey.isInstanceOf[FlowKeyIPv4]) map
          (_.asInstanceOf[FlowActionSetKey].getFlowKey) shouldBe empty
    }
}
