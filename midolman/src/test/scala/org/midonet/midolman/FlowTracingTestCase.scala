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
package org.midonet.midolman

import akka.actor.Props
import akka.testkit.TestActorRef
import com.codahale.metrics.MetricRegistry
import java.util.UUID
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch, TimeUnit}
import org.junit.runner.RunWith
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.{Bridge => ClusterBridge, Chain}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.rules.TraceRule
import org.midonet.midolman.PacketWorkflow.PipelinePath
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
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.{Datapath, DpPort, FlowMatch, FlowMatches, Packet}
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.FlowKeys.tunnel
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class FlowTracingTestCase extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor),
        VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper))

    var ddaRef: TestActorRef[TestableDDA] = _
    var datapath: Datapath = null

    // topology
    var host0: Host = _
    var bridge0: ClusterBridge = _
    var port0: BridgePort = _
    var chain0: Chain = _

    lazy val metricsReg = injector.getInstance(classOf[MetricRegistry])

    override def beforeTest() {
    }

    private def setupTopologyAndGetChain() : UUID = {
        host0 = newHost("host0", hostId)
        bridge0 = newBridge("bridge0")
        port0 = newBridgePort(bridge0)

        chain0 = newInboundChainOnBridge("my-chain", bridge0)

        chain0.getId
    }

    private def newTraceRule(chain: UUID, condition: Condition, pos: Int) {
        val traceRule = new TraceRule(condition)
            .setChainId(chain).setPosition(pos)
        clusterDataClient.rulesCreate(traceRule)
    }

    private def createDDA(preWorkflowHook: PacketContext => Unit,
                          postWorkflowHook: PacketContext => Unit,
                          packetOut: Int => Unit): TestActorRef[TestableDDA] = {
        if (ddaRef != null)
            actorSystem.stop(ddaRef)

        fetchTopology(bridge0, port0, chain0)

        val ddaProps = Props {
            new TestableDDA(new CookieGenerator(1, 1),
                mockDpChannel, clusterDataClient,
                new PacketPipelineMetrics(metricsReg),
                packetOut,
                preWorkflowHook, postWorkflowHook,
                5000)
        }

        ddaRef = TestActorRef(ddaProps)(actorSystem)
        ddaRef ! DatapathController.DatapathReady(datapath, new DatapathState {
            override def getDpPortForInterface(itfName: String): Option[DpPort] = ???
            override def dpPortNumberForTunnelKey(tunnelKey: Long): Option[DpPort] = ???
            override def getVportForDpPortNumber(portNum: Integer): Option[UUID] =
                { Some(port0.getId) }
            override def getDpPortNumberForVport(vportId: UUID): Option[Integer] = ???
            override def getDpPortName(num: Integer): Option[String] = ???
            override def host = new ResolvedHost(UUID.randomUUID(), true, "",
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

    private def makeUniqueFrame(variation: Short) =
        makeFrame(variation) << payload(UUID.randomUUID().toString)

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
            val chainId = setupTopologyAndGetChain()
            newTraceRule(chainId, newCondition(), 1)
            var packetsOut = 0
            var postWorkflowLatch = new CountDownLatch(1)

            val preQueue = new ArrayBlockingQueue[Boolean](10)
            val postQueue = new ArrayBlockingQueue[Boolean](10)
            val ddaRef = createDDA(ctx => { preQueue.add(ctx.tracingEnabled) },
                                   ctx => {
                                       postQueue.add(ctx.tracingEnabled)
                                       postWorkflowLatch.countDown },
                                   c => { packetsOut += 1 })

            ddaRef ! DeduplicationActor.HandlePackets(List(makePacket(1)).toArray)

            postWorkflowLatch.await(3, TimeUnit.SECONDS) should be (true)
            packetsOut should be (1)

            preQueue.size should be (1)
            preQueue.take should be (false)

            postQueue.size should be (1)
            postQueue.take should be (true)
        }

        scenario("tracing enabled by specific rule match") {
            val chainId = setupTopologyAndGetChain()
            newTraceRule(chainId, newCondition(tpDst = Some(500)), 1)
            var packetsOut = 0
            var postWorkflowLatch = new CountDownLatch(3)

            val preQueue = new ArrayBlockingQueue[Boolean](10)
            val postQueue = new ArrayBlockingQueue[Boolean](10)
            val ddaRef = createDDA(ctx => { preQueue.add(ctx.tracingEnabled) },
                                   ctx => {
                                       println("processed a packet")
                                       postQueue.add(ctx.tracingEnabled)
                                       postWorkflowLatch.countDown },
                                   c => { packetsOut += 1 })

            ddaRef ! DeduplicationActor.HandlePackets(
                List(makePacket(1), makePacket(500), makePacket(1000)).toArray)

            postWorkflowLatch.await(3, TimeUnit.SECONDS) should be (true)
            packetsOut should be (3)

            preQueue.size should be (3)
            preQueue.take should be (false)
            preQueue.take should be (false)
            preQueue.take should be (false)

            postQueue.size should be (3)
            postQueue.take should be (false)
            postQueue.take should be (true)
            postQueue.take should be (false)
        }
    }

    class TestableDDA(cookieGen: CookieGenerator,
                      dpChannel: DatapathChannel,
                      clusterDataClient: DataClient,
                      metrics: PacketPipelineMetrics,
                      packetOut: Int => Unit,
                      preWorkflowHook: PacketContext => Unit,
                      postWorkflowHook: PacketContext => Unit,
                      override val simulationExpireMillis: Long)
            extends DeduplicationActor(injector.getInstance(classOf[MidolmanConfig]),
                                       cookieGen, dpChannel, clusterDataClient,
                                       new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](),
                                       new ShardedFlowStateTable[NatKey, NatBinding](),
                                       new MockStateStorage(),
                                       HappyGoLuckyLeaser,
                                       metrics, packetOut) {

        override def startWorkflow(context: PacketContext): Unit = {
            preWorkflowHook(context)
            super.startWorkflow(context)
        }

        override def complete(pktCtx: PacketContext, path: PipelinePath): Unit = {
            super.complete(pktCtx, path)
            postWorkflowHook(pktCtx)
        }
    }
}
