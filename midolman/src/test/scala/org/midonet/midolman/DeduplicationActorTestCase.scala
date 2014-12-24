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

import java.util.UUID

import scala.collection.JavaConverters._
import scala.concurrent.Promise

import akka.actor.Props
import akka.testkit.TestActorRef

import com.codahale.metrics.{MetricFilter, MetricRegistry}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.DataClient
import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.PacketWorkflow.Simulation
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.{HappyGoLuckyLeaser, MockStateStorage}
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.odp.{Datapath, DpPort, FlowMatch, FlowMatches, Packet}
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable

@RunWith(classOf[JUnitRunner])
class DeduplicationActorTestCase extends MidolmanSpec {
    var datapath: Datapath = null
    var packetsSeen = List[(Packet, Int)]()
    var ddaRef: TestActorRef[TestableDDA] = _
    def dda = ddaRef.underlyingActor
    var packetsOut = 0

    lazy val metricsReg = injector.getInstance(classOf[MetricRegistry])

    override def beforeTest() {
        datapath = mockDpConn().futures.datapathsCreate("midonet").get()
        createDda()
    }

    def createDda(simulationExpireMillis: Long = 5000L): Unit = {
        if (ddaRef != null)
            actorSystem.stop(ddaRef)

        metricsReg.removeMatching(MetricFilter.ALL)

        val ddaProps = Props {
            new TestableDDA(new CookieGenerator(1, 1),
            mockDpChannel(), clusterDataClient(),
            new PacketPipelineMetrics(metricsReg),
            (x: Int) => { packetsOut += x },
            simulationExpireMillis)
        }

        ddaRef = TestActorRef(ddaProps)(actorSystem)
        dda should not be null
        ddaRef ! DatapathController.DatapathReady(datapath, new DatapathState {
            override def getDpPortForInterface(itfName: String): Option[DpPort] = ???
            override def dpPortNumberForTunnelKey(tunnelKey: Long): Option[DpPort] = ???
            override def getVportForDpPortNumber(portNum: Integer): Option[UUID] = ???
            override def getDpPortNumberForVport(vportId: UUID): Option[Integer] = ???
            override def getDpPortName(num: Integer): Option[String] = ???
            override def host = new ResolvedHost(UUID.randomUUID(), true, 0, "",
                                                 Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[Route] = ???
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = ???
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = ???
            override def vtepTunnellingOutputAction: FlowActionOutput = ???
            override def getDescForInterface(itfName: String) = ???
        })
        dda.hookPacketHandler()
    }

    def makeFrame(variation: Short) =
        { eth addr "01:02:03:04:05:06" -> "10:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
        { udp ports 10101 ---> variation }

    def makeUniqueFrame(variation: Short) =
        makeFrame(variation) << payload(UUID.randomUUID().toString)

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder): Packet = {
        val frame: Ethernet = ethBuilder
        new Packet(frame, FlowMatches.fromEthernetPacket(frame))
              .setReason(Packet.Reason.FlowTableMiss)
    }

    def makePacket(variation: Short): Packet = makeFrame(variation)

    def makeUniquePacket(variation: Short): Packet = makeUniqueFrame(variation)

    feature("DeduplicationActor handles packets") {
        scenario("pends packets that have the same match") {
            Given("four identical packets")
            val pkts = List(makePacket(1), makePacket(1), makePacket(1), makePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("the DDA should execute exactly one workflow")
            packetsSeen.length should be (1)

            And("exactly one packet should be pended")
            dda.suspended(pkts(0).getMatch) should be (Set(pkts.head))

            And("packetOut should have been called with the correct number")
            packetsOut should be (4)
        }

        scenario("discards packets when ApplyFlow has no actions") {
            Given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("some packets should be pended")
            dda.suspended(pkts(0).getMatch) should have size 2

            And("packetsOut should be called with the correct number")
            packetsOut should be (3)

            When("the dda is told to apply the flow with empty actions")
            dda.complete(pkts(0).getMatch, Nil)

            Then("the packets should be dropped")
            mockDpChannel().packetsSent should be (empty)
            dda.suspended(pkts(0).getMatch) should be (null)
        }

        scenario("emits packets when ApplyFlow contains actions") {
            Given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("some packets should be pended")
            dda.suspended(pkts(0).getMatch) should not be null

            And("packetsOut should be called with the correct number")
            packetsOut should be (3)

            When("the dda is told to apply the flow with an output action")
            dda.complete(pkts(0).getMatch, List(output(1)))

            Then("the packets should be sent to the datapath")
            val actual = mockDpChannel().packetsSent.asScala.toList.sortBy { _.## }
            val expected = pkts.tail.sortBy { _.## }
            actual should be (expected)

            And("no pended packets should remain")
            dda.suspended(pkts(0).getMatch) should be (null)
        }

        scenario("executes packets that hit the actions cache") {
            Given("an entry in the actions cache")
            val pkts = List(makePacket(1))
            dda.addToActionsCache(pkts(0).getMatch -> List(output(1)))

            When("a packet comes that hits the actions cache")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("the DDA should execute that packet directly")
            mockDpChannel().packetsSent.asScala should be (pkts)

            And("no pended packets should remain")
            dda.suspended(pkts(0).getMatch) should be (null)

            And("packetsOut should be called with the correct number")
            packetsOut should be (1)
        }

        scenario("simulates sequences of packets from the datapath") {
            Given("4 packets with 3 different matches")
            val pkts = List(makePacket(1), makePacket(2), makePacket(3), makePacket(2))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            And("a packetOut should have been called for the pending packets")
            packetsOut should be (4)

            Then("3 packet workflows should be executed")
            packetsSeen map (_._2) should be (1 to 3)

            And("one packet should be pended")
            dda.suspended(pkts(0).getMatch) should not be null
            dda.suspended(pkts(1).getMatch) should have size 1
            dda.suspended(pkts(2).getMatch) should not be null
        }

        scenario("simulates generated packets") {
            Given("a simulation that generates a packet")
            val pkt = makePacket(1)
            ddaRef ! DeduplicationActor.HandlePackets(Array(pkt))

            When("the simulation completes")
            val id = UUID.randomUUID()
            val frame: Ethernet = makeFrame(1)
            dda.completeWithGenerated(pkt.getMatch, List(output(1)),
                                      GeneratedPacket(id, frame))

            Then("the generated packet should be simulated")
            packetsSeen map { _._1.getEthernet } should be (List(pkt.getEthernet, frame))

            And("packetsOut should not be called for the generated packet")
            packetsOut should be (1)
        }

        scenario("expires packets") {
            Given("a pending packet in the DDA")
            createDda(0)
            val pkts = List(makePacket(1), makePacket(1))
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)
            // simulationExpireMillis is 0, pended packets should be
            // expired immediately
            dda.suspended(pkts(0).getMatch) should be (null)
            packetsOut should be (2)

            When("putting another packet handler in the waiting room")
            val pkt2 = makePacket(2)
            ddaRef ! DeduplicationActor.HandlePackets(Array(pkt2))
            dda.suspended(pkt2.getMatch) should not be null

            And("packetsOut should be called with the correct number")
            packetsOut should be (3)
        }
    }

    class MockPacketHandler(actionsCache: ActionsCache) extends PacketHandler {
        var p = Promise[Any]()
        var generatedPacket: GeneratedPacket = _

        override def start(pktCtx: PacketContext) = {
            pktCtx.runs += 1
            if (pktCtx.runs == 1) {
                packetsSeen = packetsSeen :+ (pktCtx.packet, pktCtx.cookie)
                if (pktCtx.isGenerated) {
                    Simulation
                } else {
                    throw new NotYetException(p.future)
                }
            } else if (pktCtx.runs == 2) {
                // re-suspend the packet (with a completed future)
                throw new NotYetException(p.future)
            } else {
                if (generatedPacket ne null) {
                    pktCtx.packetEmitter.schedule(generatedPacket)
                    generatedPacket = null
                }
                Simulation
            }
        }

        override def drop(pktCtx: PacketContext) {
        }

        def complete(wcmatch: FlowMatch, actions: List[FlowAction]): Unit = {
            actionsCache.actions.put(wcmatch, actions.asJava)
            p success null
        }
    }

    class TestableDDA(cookieGen: CookieGenerator,
                      dpChannel: DatapathChannel,
                      clusterDataClient: DataClient,
                      metrics: PacketPipelineMetrics,
                      packetOut: Int => Unit,
                      override val simulationExpireMillis: Long)
            extends DeduplicationActor(injector.getInstance(classOf[MidolmanConfig]),
                                       cookieGen, dpChannel, clusterDataClient,
                                       new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](),
                                       new ShardedFlowStateTable[NatKey, NatBinding](),
                                       new MockStateStorage(),
                                       HappyGoLuckyLeaser,
                                       metrics, packetOut)
            with MessageAccumulator {

        implicit override val dispatcher = this.context.dispatcher

        def suspended(flowMatch: FlowMatch): collection.Set[Packet] =
            suspendedPackets.get(flowMatch)

        def complete(flowMatch: FlowMatch, actions: List[FlowAction]): Unit = {
            workflow.asInstanceOf[MockPacketHandler].complete(flowMatch, actions)
        }

        def completeWithGenerated(flowMatch: FlowMatch, actions: List[FlowAction],
                                  generatedPacket: GeneratedPacket): Unit = {
            workflow.asInstanceOf[MockPacketHandler].generatedPacket = generatedPacket
            complete(flowMatch, actions)
        }

        def addToActionsCache(entry: (FlowMatch, List[FlowAction])): Unit =
            actionsCache.actions.put(entry._1, entry._2.asJava)

        def hookPacketHandler(): Unit =
            workflow = new MockPacketHandler(actionsCache)
    }
}
