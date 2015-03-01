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

import java.util.UUID

import scala.collection.mutable.Queue

import akka.actor.Props
import akka.testkit.TestActorRef
import com.codahale.metrics.MetricRegistry

import org.junit.runner.RunWith
import org.midonet.midolman.flows.FlowInvalidator
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.{Bridge => ClusterBridge, Chain}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.rules.TraceRule
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.{HappyGoLuckyLeaser, MockStateStorage}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{Datapath, DpPort, FlowMatches, Packet}
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable

@RunWith(classOf[JUnitRunner])
class FlowTracingTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor),
                   VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper))

    var ddaRef: TestActorRef[TestableDDA] = _
    var datapath: Datapath = null

    var bridge: ClusterBridge = _
    var port: BridgePort = _
    var chain: Chain = _

    override def beforeTest(): Unit = {
        bridge = newBridge("bridge0")
        port = newBridgePort(bridge)
        chain = newInboundChainOnBridge("my-chain", bridge)
    }

    private def newTraceRule(chain: UUID, condition: Condition, pos: Int) {
        val traceRule = new TraceRule(condition).setChainId(chain).setPosition(pos)
        clusterDataClient.rulesCreate(traceRule)
    }

    private def createDDA(packetOut: Int => Unit,
                          preQueue: Queue[Boolean],
                          postQueue: Queue[Boolean]) = {
        fetchTopology(bridge, port, chain)

        val ddaProps = Props {
            new TestableDDA(new CookieGenerator(1, 1), mockDpChannel, clusterDataClient,
                new PacketPipelineMetrics(injector.getInstance(classOf[MetricRegistry])),
                packetOut, preQueue, postQueue, 5000L)
        }

        ddaRef = TestActorRef(ddaProps)(actorSystem)
        ddaRef ! DatapathController.DatapathReady(datapath, new DatapathState {
            override def getDpPortForInterface(itfName: String): Option[DpPort] = ???
            override def dpPortNumberForTunnelKey(tunnelKey: Long): Option[DpPort] = ???
            override def getVportForDpPortNumber(portNum: Integer): Option[UUID] =
                { Some(port.getId) }
            override def getDpPortNumberForVport(vportId: UUID): Option[Integer] = ???
            override def getDpPortName(num: Integer): Option[String] = ???
            override def host = new ResolvedHost(UUID.randomUUID(), true,
                                                 Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[Route] = ???
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = ???
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = ???
            override def vtepTunnellingOutputAction: FlowActionOutput = ???
            override def getDescForInterface(itfName: String) = ???
        })
        ddaRef
    }

    private def makeFrame(variation: Short) =
        { eth addr "00:02:03:04:05:06" -> "00:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
        { udp ports 10101 ---> variation }

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder[Ethernet]): Packet = {
        val frame: Ethernet = ethBuilder
        val flowMatch = FlowMatches.fromEthernetPacket(frame)
        val pkt = new Packet(frame, flowMatch)
        flowMatch.setInputPortNumber(42)
        pkt
    }

    def makePacket(variation: Short): Packet = makeFrame(variation)

    feature("Tracing enabled by rule in chain") {
        scenario("tracing enabled by catchall rule") {
            newTraceRule(chain.getId, newCondition(), 1)
            var packetsOut = 0
            val preQueue = Queue[Boolean]()
            val postQueue = Queue[Boolean]()
            val ddaRef = createDDA(packetsOut += _, preQueue, postQueue)

            ddaRef ! DeduplicationActor.HandlePackets(List(makePacket(1)).toArray)

            packetsOut should be (1)
            preQueue should be (Queue(false))
            postQueue should be (Queue(true))
        }

        scenario("tracing enabled by specific rule match") {
            newTraceRule(chain.getId, newCondition(tpDst = Some(500)), 1)
            var packetsOut = 0
            val preQueue = Queue[Boolean]()
            val postQueue = Queue[Boolean]()
            val ddaRef = createDDA(packetsOut += _, preQueue, postQueue)

            ddaRef ! DeduplicationActor.HandlePackets(
                List(makePacket(1), makePacket(500), makePacket(1000)).toArray)

            packetsOut should be (3)
            preQueue should be (Queue(false, false, false))
            postQueue should be (Queue(false, true, false))
        }
    }

    class TestableDDA(cookieGen: CookieGenerator,
                      dpChannel: DatapathChannel,
                      clusterDataClient: DataClient,
                      metrics: PacketPipelineMetrics,
                      packetOut: Int => Unit,
                      preQueue: Queue[Boolean],
                      postQueue: Queue[Boolean],
                      override val simulationExpireMillis: Long)
            extends DeduplicationActor(injector.getInstance(classOf[MidolmanConfig]),
                                       cookieGen, dpChannel, clusterDataClient,
                                       new FlowInvalidator(null),
                                       new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](),
                                       new ShardedFlowStateTable[NatKey, NatBinding](),
                                       new MockStateStorage(),
                                       HappyGoLuckyLeaser,
                                       metrics, packetOut) {

        override def startWorkflow(context: PacketContext): Unit = {
            preQueue += context.tracingEnabled
            super.startWorkflow(context)
            postQueue += context.tracingEnabled
        }
    }
}
