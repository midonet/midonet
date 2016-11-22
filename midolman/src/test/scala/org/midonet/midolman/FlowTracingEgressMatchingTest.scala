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
package org.midonet.midolman

import java.util.{LinkedList, UUID}
import java.{util => ju}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import akka.testkit.TestActorRef

import com.google.common.collect.{BiMap, HashBiMap}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.layer3.{Route => L3Route}
import org.midonet.midolman.simulation.{Bridge => SimBridge, PacketContext, Port, Router => SimRouter}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{FlowStateAgentPackets => FlowStatePackets, TraceState}
import org.midonet.midolman.util.{MidolmanSpec, MockPacketWorkflow}
import org.midonet.midolman.util.mock.MockDatapathChannel
import org.midonet.odp.flows._
import org.midonet.odp.{Flow, FlowMatches, Packet}
import org.midonet.packets._
import org.midonet.packets.TunnelKeys.TraceBit
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable

@RunWith(classOf[JUnitRunner])
class FlowTracingEgressMatchingTest extends MidolmanSpec {
    var ingressHost: UUID = null
    var egressHost: UUID = null
    var egressHostIp = IPv4Addr("180.0.1.3")

    var router: UUID = null
    var uplinkPort: UUID = null
    var rtrIntPort: UUID = null

    var bridge: UUID = null
    var bridgeChain: UUID = null
    var bridgeRtrPort: UUID = null
    var bridgeVm1Port: UUID = null
    var bridgeVm2Port: UUID = null

    val tunnelPort = 10001

    // router port details
    private val uplinkGatewayAddr = IPv4Addr("180.0.1.1")
    private val uplinkGatewayMac: MAC = "02:0b:09:07:05:03"
    private val uplinkNwAddr = new IPv4Subnet("180.0.1.0", 24)
    private val uplinkPortAddr = IPv4Addr("180.0.1.2")
    private val uplinkPortMac: MAC = "02:0a:08:06:04:02"

    val rtrIntIp = new IPv4Subnet("10.0.0.254", 24)
    val rtrIntMac = MAC.fromString("22:aa:aa:ff:ff:ff")

    val vm1IpAddr = IPv4Addr("10.0.0.1")
    val vm1Mac = MAC.fromString("22:ff:bb:cc:cc:cc")
    val vm2IpAddr = IPv4Addr("10.0.0.2")
    val vm2Mac = MAC.fromString("22:ff:bb:cc:cc:cd")

    private val traceTable =
        new ShardedFlowStateTable[TraceKey, TraceContext](clock).addShard()

    // infra for ingress dda
    private val portMapIngress: BiMap[Int,UUID] = HashBiMap.create()
    private val mockDpIngress = new MockDatapathChannel
    private var pktWkflIngress: MockPacketWorkflow = null
    private val packetOutQueueIngress = new LinkedList[(Packet, ju.List[FlowAction])]
    private val statePacketOutQueueIngress = new LinkedList[(Packet, ju.List[FlowAction])]
    private val flowQueueIngress = new LinkedList[Flow]
    private val packetCtxTrapIngress = new LinkedList[PacketContext]

    // infra for egress dda
    private val portMapEgress: BiMap[Int,UUID] = HashBiMap.create()
    private val mockDpEgress = new MockDatapathChannel
    private var pktWkflEgress: MockPacketWorkflow = null
    private val packetOutQueueEgress = new LinkedList[(Packet, ju.List[FlowAction])]
    private val statePacketOutQueueEgress = new LinkedList[(Packet, ju.List[FlowAction])]
    private val flowQueueEgress = new LinkedList[Flow]
    private val packetCtxTrapEgress = new LinkedList[PacketContext]

    override def beforeTest(): Unit = {
        ingressHost = hostId
        egressHost = newHost("egressHost")

        router = newRouter("router")
        uplinkPort = newRouterPort(router, uplinkPortMac,
                                   uplinkPortAddr.toString,
                                   uplinkNwAddr.getAddress.toString,
                                   uplinkNwAddr.getPrefixLen)
        materializePort(uplinkPort, ingressHost, "uplinkPort")

        newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
                 NextHop.PORT, uplinkPort, uplinkGatewayAddr.toString, 1)
        newRoute(router, "0.0.0.0", 0, uplinkNwAddr.getAddress.toString,
                 uplinkNwAddr.getPrefixLen, NextHop.PORT, uplinkPort,
                 new IPv4Addr(L3Route.NO_GATEWAY).toString, 10)
        rtrIntPort = newRouterPort(router, rtrIntMac, rtrIntIp.toUnicastString,
                                   rtrIntIp.toNetworkAddress.toString,
                                   rtrIntIp.getPrefixLen)
        newRoute(router, "0.0.0.0", 0,
                 rtrIntIp.toNetworkAddress.toString, rtrIntIp.getPrefixLen,
                 NextHop.PORT, rtrIntPort,
                 new IPv4Addr(L3Route.NO_GATEWAY).toString, 10)

        bridge = newBridge("bridge")
        bridgeRtrPort = newBridgePort(bridge)
        linkPorts(rtrIntPort, bridgeRtrPort)

        bridgeChain = newInboundChainOnBridge("my-chain", bridge)

        // vm1 on egress host
        bridgeVm1Port = newBridgePort(bridge)
        materializePort(bridgeVm1Port, egressHost, "vm1Port")

        // vm2 on ingress host
        bridgeVm2Port = newBridgePort(bridge)
        materializePort(bridgeVm2Port, ingressHost, "vm2Port")


        val simRouter: SimRouter = fetchDevice[SimRouter](router)
        val simBridge: SimBridge = fetchDevice[SimBridge](bridge)

        feedArpTable(simRouter, uplinkGatewayAddr.addr, uplinkGatewayMac)
        feedArpTable(simRouter, uplinkPortAddr.addr, uplinkPortMac)
        feedArpTable(simRouter, rtrIntIp.getAddress.addr, rtrIntMac)
        feedArpTable(simRouter, vm1IpAddr.addr, vm1Mac)
        feedArpTable(simRouter, vm2IpAddr.addr, vm2Mac)

        feedMacTable(simBridge, vm1Mac, bridgeVm1Port)
        feedMacTable(simBridge, vm2Mac, bridgeVm2Port)

        fetchPorts(uplinkPort, bridgeVm2Port) map {
            case p: Port => portMapIngress.put(p.tunnelKey.toInt, p.id)
            case _ =>
        }
        fetchPorts(bridgeVm1Port) map {
            case p: Port => portMapEgress.put(p.tunnelKey.toInt, p.id)
            case _ =>
        }
        fetchPorts(rtrIntPort, bridgeRtrPort)
        fetchChains(bridgeChain)
        fetchDevice[SimRouter](router)
        fetchDevice[SimBridge](bridge)

        val output = FlowActions.output(23)
        pktWkflIngress = packetWorkflow(
            dpPortToVport = portMapIngress.toMap[Int, UUID],
            peers =  Map(egressHost -> Route(uplinkPortAddr, egressHostIp, output)),
            tunnelPorts = List(tunnelPort),
            traceTable = traceTable,
            dpChannel = mockDpIngress,
            packetCtxTrap = packetCtxTrapIngress
        )(ingressHost)

        mockDpIngress.packetsExecuteSubscribe(
            (packet, actions) => packetOutQueueIngress.add((packet,actions)) )
        mockDpIngress.statePacketsExecuteSubscribe(
            (packet, actions) => statePacketOutQueueIngress.add((packet,actions)) )
        mockDpIngress.flowCreateSubscribe(flow => flowQueueIngress.add(flow))

        pktWkflEgress = packetWorkflow(
            dpPortToVport = portMapEgress.toMap[Int, UUID],
            peers =  Map(ingressHost -> Route(egressHostIp, uplinkPortAddr, output)),
            tunnelPorts = List(tunnelPort),
            traceTable = traceTable,
            dpChannel = mockDpEgress,
            packetCtxTrap = packetCtxTrapEgress
        )(egressHost)
        mockDpEgress.packetsExecuteSubscribe(
            (packet, actions) => packetOutQueueEgress.add((packet,actions)) )
        mockDpEgress.statePacketsExecuteSubscribe(
            (packet, actions) => statePacketOutQueueEgress.add((packet,actions)) )
        mockDpEgress.flowCreateSubscribe(flow => flowQueueEgress.add(flow))
    }

    feature("tunnel tagging") {
        scenario("Tunnel key is tagged with trace mask, on bridge") {
            newTraceRuleOnChain(bridgeChain, 1,
                                newCondition(tpDst = Some(500)),
                                UUID.randomUUID)

            val frame = { eth src vm2Mac dst vm1Mac } <<
            { ip4 src vm2IpAddr dst vm1IpAddr } <<
            { tcp src 23423 dst 500 } << payload("foobar")

            val inPortNum = portMapIngress.inverse.get(bridgeVm2Port)
            injectPacketVerifyTraced(inPortNum, frame)
        }

        scenario("Tunnel key is tagged with trace mask, traversing router") {
            newTraceRuleOnChain(bridgeChain, 1,
                                newCondition(tpDst = Some(500)),
                                UUID.randomUUID)

            val frame = { eth src uplinkGatewayMac dst uplinkPortMac } <<
            { ip4 src uplinkGatewayAddr dst vm1IpAddr } <<
            { tcp src 23423 dst 500 } << payload("foobar")

            val inPortNum = portMapIngress.inverse.get(uplinkPort)
            injectPacketVerifyTraced(inPortNum, frame)
        }
    }

    private def injectPacketVerifyTraced(inPortNum: Int,
                                         frame: Ethernet): Unit = {
        val packet1 = new Packet(frame,
                                FlowMatches.fromEthernetPacket(frame)
                                    .addKey(FlowKeys.inPort(inPortNum))
                                    .setInputPortNumber(inPortNum))
        pktWkflIngress.handlePackets(packet1)

        // should be sending a trace state to other host
        packetOutQueueIngress.size should be (1)
        statePacketOutQueueIngress.size should be (1)
        val (_, stateActions) = statePacketOutQueueIngress.remove()
        getTunnelId(stateActions) should be (FlowStatePackets.TUNNEL_KEY)

        // should have executed flow with tunnel mask set
        val (packet, actions) = packetOutQueueIngress.remove()
        TraceBit.isSet(getTunnelId(actions).toInt) shouldBe true
        getTunnelDst(actions) should be (egressHostIp)

        // should have created flow, but without tunnel mask set
        flowQueueIngress.size should be (1)
        val flow = flowQueueIngress.remove()
        TraceBit.isSet(
            getTunnelId(flow.getActions).toInt) shouldBe false

        packetCtxTrapIngress.size should be (2)
        val ingressCtx = packetCtxTrapIngress.remove()
        // should have the same packet context after the workflow restart
        ingressCtx should be (packetCtxTrapIngress.pop())

        // add it to the trace table so egress can find it
        traceTable.putAndRef(ingressCtx.traceKeyForEgress,
                             ingressCtx.traceContext)

        val egressFrame = applyPacketActions(packet.getEthernet,
                                             actions)
        val egressFrameFlowMatch = FlowMatches.fromEthernetPacket(egressFrame)
        actions.asScala.collect {
            case f: FlowActionSetKey if f.getFlowKey.isInstanceOf[FlowKeyTunnel] =>
                f.getFlowKey
        } foreach egressFrameFlowMatch.addKey
        egressFrameFlowMatch.addKey(FlowKeys.inPort(tunnelPort))
            .setInputPortNumber(tunnelPort)

        val packet2 = new Packet(egressFrame, egressFrameFlowMatch)
        pktWkflEgress.handlePackets(packet2)

        val (_, actions2) = packetOutQueueEgress.remove()

        getOutputPort(actions2) should be (portMapEgress.inverse
                                               .get(bridgeVm1Port))
        val egressCtx = packetCtxTrapEgress.remove()
        egressCtx.traceFlowId should be (ingressCtx.traceFlowId())

        // shouldn't create a flow for trace packets
        flowQueueEgress.size should be (0)
    }

    private def getTunnelId(actions: ju.List[FlowAction]): Long = {
        val tunIds = actions.asScala.collect(
            { case a: FlowActionSetKey
                     if a.getFlowKey.isInstanceOf[FlowKeyTunnel] =>
                a.getFlowKey.asInstanceOf[FlowKeyTunnel].tun_id })
        tunIds.size should be (1)
        tunIds(0)
    }

    private def getTunnelDst(actions: ju.List[FlowAction]): IPv4Addr = {
        val dstIps = actions.asScala.collect(
            { case a: FlowActionSetKey
                     if a.getFlowKey.isInstanceOf[FlowKeyTunnel] =>
                IPv4Addr.fromInt(
                    a.getFlowKey.asInstanceOf[FlowKeyTunnel].ipv4_dst) })
        dstIps.size should be (1)
        dstIps(0)
    }

    private def getOutputPort(actions: ju.List[FlowAction]): Int = {
        val outputPorts = actions.asScala.collect(
            { case a: FlowActionOutput => a.getPortNumber })
        outputPorts.size should be (1)
        outputPorts(0)
    }
}
