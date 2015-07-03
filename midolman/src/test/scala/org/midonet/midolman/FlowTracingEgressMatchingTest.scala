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

import java.{util => ju}
import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.JavaConversions._

import akka.testkit.TestActorRef
import com.google.common.collect.{BiMap, HashBiMap}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Chain}
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.cluster.data.rules.{TraceRule => TraceRuleData}
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.layer3.{Route => L3Route}
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.{Bridge => SimBridge, PacketContext}
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.state.FlowStatePackets
import org.midonet.midolman.state.TraceState
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.topology._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MockDatapathChannel
import org.midonet.odp.{Flow, FlowMatches, Packet}
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable

@RunWith(classOf[JUnitRunner])
class FlowTracingEgressMatchingTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))
    var ingressHost: UUID = null
    var egressHost: UUID = null
    var egressHostIp = IPv4Addr("180.0.1.3")

    var router: UUID = null
    var uplinkPort: RouterPort = null
    var rtrIntPort: RouterPort = null

    var bridge: UUID = null
    var bridgeChain: Chain = null
    var bridgeRtrPort: BridgePort = null
    var bridgeVm1Port: BridgePort = null
    var bridgeVm2Port: BridgePort = null

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
    private var pktWkflIngress: TestActorRef[PacketWorkflow] = null
    private val packetOutQueueIngress = new ju.LinkedList[(Packet, ju.List[FlowAction])]
    private val flowQueueIngress = new ju.LinkedList[Flow]
    private val packetCtxTrapIngress = new ju.LinkedList[PacketContext]

    // infra for egress dda
    private val portMapEgress: BiMap[Int,UUID] = HashBiMap.create()
    private val mockDpEgress = new MockDatapathChannel
    private var pktWkflEgress: TestActorRef[PacketWorkflow] = null
    private val packetOutQueueEgress = new ju.LinkedList[(Packet, ju.List[FlowAction])]
    private val flowQueueEgress = new ju.LinkedList[Flow]
    private val packetCtxTrapEgress = new ju.LinkedList[PacketContext]

    override def beforeTest(): Unit = {
        ingressHost = newHost("ingressHost", hostId)
        egressHost = newHost("egressHost")

        router = newRouter("router")
        uplinkPort = newRouterPort(router, uplinkPortMac,
                                   uplinkPortAddr.toString,
                                   uplinkNwAddr.getAddress.toString,
                                   uplinkNwAddr.getPrefixLen)
        materializePort(uplinkPort, ingressHost, "uplinkPort")
        portMapIngress.put(uplinkPort.getTunnelKey, uplinkPort.getId)

        newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
                 NextHop.PORT, uplinkPort.getId, uplinkGatewayAddr.toString, 1)
        newRoute(router, "0.0.0.0", 0, uplinkNwAddr.getAddress.toString,
                 uplinkNwAddr.getPrefixLen, NextHop.PORT, uplinkPort.getId,
                 new IPv4Addr(L3Route.NO_GATEWAY).toString, 10)
        rtrIntPort = newRouterPort(router, rtrIntMac, rtrIntIp.toUnicastString,
                                   rtrIntIp.toNetworkAddress.toString,
                                   rtrIntIp.getPrefixLen)
        newRoute(router, "0.0.0.0", 0,
                 rtrIntIp.toNetworkAddress.toString, rtrIntIp.getPrefixLen,
                 NextHop.PORT, rtrIntPort.getId,
                 new IPv4Addr(L3Route.NO_GATEWAY).toString, 10)

        bridge = newBridge("bridge")
        bridgeRtrPort = newBridgePort(bridge)
        clusterDataClient.portsLink(rtrIntPort.getId, bridgeRtrPort.getId)

        bridgeChain = newInboundChainOnBridge("my-chain", bridge)

        // vm1 on egress host
        bridgeVm1Port = newBridgePort(bridge)
        materializePort(bridgeVm1Port, egressHost, "vm1Port")
        portMapEgress.put(bridgeVm1Port.getTunnelKey, bridgeVm1Port.getId)

        // vm2 on ingress host
        bridgeVm2Port = newBridgePort(bridge)
        materializePort(bridgeVm2Port, ingressHost, "vm2Port")
        portMapIngress.put(bridgeVm2Port.getTunnelKey, bridgeVm2Port.getId)

        val simRouter: SimRouter = fetchDevice[SimRouter](router)
        val simBridge: SimBridge = fetchDevice[SimBridge](bridge)

        feedArpTable(simRouter, uplinkGatewayAddr.addr, uplinkGatewayMac)
        feedArpTable(simRouter, uplinkPortAddr.addr, uplinkPortMac)
        feedArpTable(simRouter, rtrIntIp.getAddress.addr, rtrIntMac)
        feedArpTable(simRouter, vm1IpAddr.addr, vm1Mac)
        feedArpTable(simRouter, vm2IpAddr.addr, vm2Mac)

        feedMacTable(simBridge, vm1Mac, bridgeVm1Port.getId)
        feedMacTable(simBridge, vm2Mac, bridgeVm2Port.getId)

        fetchTopology(uplinkPort, rtrIntPort,
                      bridgeRtrPort, bridgeVm1Port,
                      bridgeVm2Port, bridgeChain)
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
        )(ingressHost, clusterDataClient)

        mockDpIngress.packetsExecuteSubscribe(
            (packet, actions) => packetOutQueueIngress.add((packet,actions)) )
        mockDpIngress.flowCreateSubscribe(flow => flowQueueIngress.add(flow))

        pktWkflEgress = packetWorkflow(
            dpPortToVport = portMapEgress.toMap[Int, UUID],
            peers =  Map(ingressHost -> Route(egressHostIp, uplinkPortAddr, output)),
            tunnelPorts = List(tunnelPort),
            traceTable = traceTable,
            dpChannel = mockDpEgress,
            packetCtxTrap = packetCtxTrapEgress
        )(egressHost, clusterDataClient)
        mockDpEgress.packetsExecuteSubscribe(
            (packet, actions) => packetOutQueueEgress.add((packet,actions)) )
        mockDpEgress.flowCreateSubscribe(flow => flowQueueEgress.add(flow))
    }

    feature("tunnel tagging") {
       scenario("Tunnel key is tagged with trace mask, on bridge") {
           newTraceRule(UUID.randomUUID, bridgeChain,
                        newCondition(tpDst = Some(500)), 1)

           val frame = { eth src vm2Mac dst vm1Mac } <<
               { ip4 src vm2IpAddr dst vm1IpAddr } <<
               { tcp src 23423 dst 500 } << payload("foobar")

           val inPortNum = portMapIngress.inverse.get(bridgeVm2Port.getId)
           injectPacketVerifyTraced(inPortNum, frame)
       }

       scenario("Tunnel key is tagged with trace mask, traversing router") {
           newTraceRule(UUID.randomUUID, bridgeChain,
                        newCondition(tpDst = Some(500)), 1)

           val frame = { eth src uplinkGatewayMac dst uplinkPortMac } <<
               { ip4 src uplinkGatewayAddr dst vm1IpAddr } <<
               { tcp src 23423 dst 500 } << payload("foobar")

           val inPortNum = portMapIngress.inverse.get(uplinkPort.getId)
           injectPacketVerifyTraced(inPortNum, frame)
       }
    }

    private def injectPacketVerifyTraced(inPortNum: Int,
                                         frame: Ethernet): Unit = {
        val packets = List(new Packet(frame,
                                      FlowMatches.fromEthernetPacket(frame)
                                          .addKey(FlowKeys.inPort(inPortNum))
                                          .setInputPortNumber(inPortNum)))
        pktWkflIngress ! PacketWorkflow.HandlePackets(packets.toArray)

        // should be sending a trace state to other host
        packetOutQueueIngress.size should be (2)
        val (_, stateActions) = packetOutQueueIngress.removeLast()
        getTunnelId(stateActions) should be (FlowStatePackets.TUNNEL_KEY)

        // should have executed flow with tunnel mask set
        val (packet, actions) = packetOutQueueIngress.remove()
        TraceState.traceBitPresent(getTunnelId(actions)) should be (true)
        getTunnelDst(actions) should be (egressHostIp)

        // should have created flow, but without tunnel mask set
        flowQueueIngress.size should be (1)
        val flow = flowQueueIngress.remove()
        TraceState.traceBitPresent(
            getTunnelId(flow.getActions)) should be (false)

        packetCtxTrapIngress.size should be (2)
        val ingressCtx = packetCtxTrapIngress.remove()
        // should have the same packet context after the workflow restart
        ingressCtx should be (packetCtxTrapIngress.pop())

        val egressFrame = applyPacketActions(packet.getEthernet(),
                                             actions)
        val egressFrameFlowMatch =
            FlowMatches.fromEthernetPacket(egressFrame)
        egressFrameFlowMatch.addKeys(
            actions.asScala.collect(
                { case f: FlowActionSetKey
                         if f.getFlowKey.isInstanceOf[FlowKeyTunnel] =>
                    f.getFlowKey }).asJava)
        egressFrameFlowMatch.addKey(FlowKeys.inPort(tunnelPort))
            .setInputPortNumber(tunnelPort)

        val packets2 = List(new Packet(egressFrame, egressFrameFlowMatch))
        pktWkflEgress ! PacketWorkflow.HandlePackets(packets2.toArray)

        val (_, actions2) = packetOutQueueEgress.remove()

        getOutputPort(actions2) should be (portMapEgress.inverse
                                               .get(bridgeVm1Port.getId))
        val egressCtx = packetCtxTrapEgress.remove()
        egressCtx.traceFlowId should be (ingressCtx.traceFlowId())

        // shouldn't create a flow for trace packets
        flowQueueEgress.size should be (0)
    }

    private def newTraceRule(requestId: UUID, chain: Chain,
                             condition: Condition, pos: Int): Unit = {
        val traceRule = new TraceRuleData(requestId, condition, Long.MaxValue)
            .setChainId(chain.getId).setPosition(pos)
        clusterDataClient.rulesCreate(traceRule)

        fetchDevice(chain)
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
