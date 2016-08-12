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

import scala.concurrent.{Future, Promise}

import akka.actor.Props
import akka.testkit.TestActorRef

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.monitoring.NullFlowRecorder
import org.midonet.midolman.simulation.PacketEmitter.GeneratedLogicalPacket
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPhysicalPacket
import org.midonet.midolman.simulation.{DhcpConfig, PacketContext}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{FlowStatePackets, HappyGoLuckyLeaser, MockFlowStateTable, MockStateStorage}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.FlowKeys.tunnel
import org.midonet.odp.flows.{FlowActions, FlowAction}
import org.midonet.odp.{Datapath, FlowMatches, Packet}
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder.{udp, _}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.util.functors.Callback0

@RunWith(classOf[JUnitRunner])
class PacketWorkflowTest extends MidolmanSpec {
    var packetsSeen = List[PacketContext]()
    var packetWorkflow: TestablePacketWorkflow = _
    var packetsOut = 0
    var stateMessagesSeen = 0

    val NoLogging = Logger(NOPLogger.NOP_LOGGER)

    val conntrackTable = new MockFlowStateTable[ConnTrackKey, ConnTrackValue]()
    val natTable = new MockFlowStateTable[NatKey, NatBinding]()

    var statePushed = false

    val cookie = 42

    override def beforeTest() {
        createPacketWorkflow()
    }

    def createPacketWorkflow(simulationExpireMillis: Long = 5000L): Unit = {
        packetWorkflow = new TestablePacketWorkflow(new CookieGenerator(1, 1),
                                                    mockDpChannel,
                                                    (x: Int) => { packetsOut += x },
                                                    simulationExpireMillis)
    }

    def makeFrame(variation: Short) =
        { eth addr "01:02:03:04:05:06" -> "10:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
        { udp ports 10101 ---> variation }

    def makeUniqueFrame(variation: Short) =
        makeFrame(variation) << payload(UUID.randomUUID().toString)

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder[Ethernet]): Packet = {
        val frame: Ethernet = ethBuilder
        new Packet(frame, FlowMatches.fromEthernetPacket(frame))
              .setReason(Packet.Reason.FlowTableMiss)
    }

    def makePacket(variation: Short): Packet = makeFrame(variation)

    def makeStatePacket(): Packet = {
        val eth = FlowStatePackets.makeUdpShell(Array())
        val fmatch = FlowMatches.fromEthernetPacket(eth)
        fmatch.addKey(tunnel(FlowStatePackets.TUNNEL_KEY, 1, 2, 0))
        new Packet(eth, fmatch)
    }

    def makeUniquePacket(variation: Short): Packet = makeUniqueFrame(variation)

    feature("Packet workflow handles packets") {
        scenario("state messages are not deduplicated") {
            Given("four identical state packets")
            val pkts = (1 to 4) map (_ => makeStatePacket())

            When("they are fed to the packet workflow")
            packetWorkflow.handlePackets(pkts:_*)

            Then("the packet workflow should accept the state")
            stateMessagesSeen should be (4)
            And("packetOut should have been called with the correct number")
            packetsOut should be (4)

            And("all state messages are consumed")
            mockDpConn().packetsSent should be (empty)
        }

        scenario("simulates sequences of packets from the datapath") {
            Given("4 packets with 3 different matches")
            val pkts = List(makePacket(1), makePacket(2), makePacket(3), makePacket(2))

            When("they are fed to the packet workflow")
            packetWorkflow.handlePackets(pkts:_*)

            Then("a packetOut should have been called for the pending packets")
            packetsOut should be (4)

            And("4 packet workflows should be executed")
            packetsSeen map (_.cookie) should be (1 to 4)
        }

        scenario("simulates generated logical packets") {
            Given("a simulation that generates a packet")
            val pkt = makePacket(1)
            packetWorkflow.handlePackets(pkt)

            When("the simulation completes")
            val id = UUID.randomUUID()
            val frame: Ethernet = makeFrame(2)
            packetWorkflow.completeWithGenerated(
                List(output(1)), GeneratedLogicalPacket(id, frame))
            packetWorkflow.process()

            Then("the generated packet should be simulated")
            packetsSeen map { _.ethernet } should be (List(pkt.getEthernet, frame))

            And("packetsOut should not be called for the generated packet")
            packetsOut should be (1)
        }

        scenario("simulates generated physical packets") {
            Given("a simulation that generates a packet")
            val pkt = makePacket(1)
            packetWorkflow.handlePackets(pkt)

            When("the simulation completes")
            val frame: Ethernet = makeFrame(2)
            packetWorkflow.completeWithGenerated(
                List(), GeneratedPhysicalPacket(2, frame))
            packetWorkflow.process()

            Then("the generated packet should be seen")
            packetsSeen map { _.ethernet } should be (List(pkt.getEthernet, frame))

            And("packetsOut should not be called for the generated packet")
            packetsOut should be (1)
        }

        scenario("expires packets") {
            Given("a pending packet in the packet workflow")
            createPacketWorkflow(0)
            val pkts = List(makePacket(1), makePacket(1))
            packetWorkflow.handlePackets(pkts:_*)
            packetsOut should be (2)

            When("putting another packet handler in the waiting room")
            val pkt2 = makePacket(2)
            packetWorkflow.handlePackets(pkt2)

            Then("packets are expired and dropped")
            isCleared(packetsSeen.head)
            isCleared(packetsSeen.drop(1).head)

            And("the modified flow match is not reset")
            packetsSeen.head.wcmatch should not be packetsSeen.head.origMatch
            packetsSeen.drop(1).head.wcmatch should not be packetsSeen.drop(1).head.origMatch

            And("packetsOut should be called with the correct number")
            packetsOut should be (3)
        }

        scenario("packet context is cleared in the waiting room") {
            Given("a simulation result")
            packetWorkflow.nextActions = List(FlowActions.output(1))

            When("a packet is placed in the waiting room")
            val pkt = makePacket(1)
            packetWorkflow.handlePackets(pkt)
            packetsOut should be (1)

            Then("the packet context should be clear")
            isCleared(packetsSeen.head)
            packetsSeen.head.origMatch should not be packetsSeen.head.wcmatch
        }

        scenario("packet context is cleared when dropping") {
            Given("a simulation that generates a packet")
            val pkt = makePacket(1)
            packetWorkflow.handlePackets(pkt)

            When("the simulation completes with an error")
            packetWorkflow.completeWithException(
                new Exception("c'est ne pas une exception"))

            Then("the packet is dropped")
            isCleared(packetsSeen.head)

            And("the modified flow match is not reset")
            packetsSeen.head.wcmatch should not be packetsSeen.head.origMatch

            And("packetsOut should be called")
            packetsOut should be (1)
        }
    }

    private def isCleared(context: PacketContext): Unit = {
        context.flowTags should be (empty)
        context.flowRemovedCallbacks should be (empty)
        context.packetActions should be (empty)
        context.flowActions should be (empty)
    }
/*
    feature("A PacketWorkflow handles results from the simulation layer") {

        scenario("A Simulation returns SendPacket") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns SendPacket")
            pktCtx.virtualFlowActions.add(output)
            pkfw.processSimulationResult(pktCtx, SendPacket)

            Then("action translation is performed")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw, checkTranslate, checkExecPacket)

            And("state is not pushed")
            statePushed should be (false)
        }

        scenario("A Simulation returns NoOp") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns NoOp")
            pkfw.processSimulationResult(pktCtx, NoOp)

            Then("the resulting actions are empty")
            runChecks(pktCtx, pkfw, checkEmptyActions _)

            And("state is not pushed")
            statePushed should be (false)
        }

        scenario("A Simulation returns Drop for userspace only match") {
            Given("a PkWf object with a userspace only match")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx, Drop)

            And("the packets gets executed (with 0 action)")
            runChecks(pktCtx, pkfw, applyEmptyActions)

            And("state is not pushed")
            statePushed should be (false)
        }

        scenario("A Simulation returns Drop") {
            Given("a PkWf object with a match which is not userspace only")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx, Drop)

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(pktCtx, pkfw, applyEmptyActions)

            And("state is not pushed")
            statePushed should be (false)
        }

        scenario("A Simulation returns TemporaryDrop for userspace only match") {
            Given("a PkWf object with a userspace only match")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx, TemporaryDrop)

            Then("the FlowController gets an AddWildcardFlow request")
            And("the Deduplication actor gets an empty ApplyFlow request")
            And("the packets gets executed (with 0 action)")
            runChecks(pktCtx, pkfw, applyEmptyActions)

            And("state is not pushed")
            statePushed should be (false)
        }

        scenario("A Simulation returns TemporaryDrop") {
            Given("a PkWf object with a match which is not userspace only")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx, TemporaryDrop)

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(pktCtx, pkfw, applyEmptyActions)

            And("state is not pushed")
            statePushed should be (false)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow " +
                 "for userspace only match") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("a user space field is seen")
            pktCtx.wcmatch.getIcmpIdentifier

            And("the simulation layer returns AddVirtualWildcardFlow")
            val result = AddVirtualWildcardFlow
            pkfw.processSimulationResult(pktCtx, result)

            Then("action translation is performed")
            And("the FlowController does not get an AddWildcardFlow request")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw, checkTranslate _)

            And("state is pushed")
            statePushed should be (true)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result = AddVirtualWildcardFlow
            pkfw.processSimulationResult(pktCtx, result)

            Then("action translation is performed")
            And("the FlowController gets an AddWildcardFlow request")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw, checkTranslate _ :: applyOutputActions)

            And("state is pushed")
            statePushed should be (true)
        }
    }

      def flMatch(userspace: Boolean = false) = {
        val flm = new FlowMatch()
        if (userspace) {
            flm addKey FlowKeys.icmpEcho(0, 1, 3)
        }
        flm
    }

    def packet(userspace: Boolean = false) =
        new Packet(Ethernet.random, flMatch(userspace))

    def wcMatch(userspace: Boolean = false) = {
        val wcMatch = flMatch(userspace)
        if (userspace)
          wcMatch.getIcmpIdentifier
        wcMatch
    }

    /* helpers for checking received msgs */

    type Check = (Seq[Any], JList[FlowAction]) => Unit

    def runChecks(pktCtx: PacketContext, pw: PacketWorkflow, checks: List[Check]) {
        runChecks(pktCtx, pw, checks:_*)
    }

    def runChecks(pktCtx: PacketContext, pw: PacketWorkflow, checks: Check*) {
        def drainMessages(): List[AnyRef] = {
            receiveOne(500 millis) match {
                case null => Nil
                case msg => msg :: drainMessages()
            }
        }
        val msgs = drainMessages()
        checks foreach { _(msgs, pktCtx.flowActions) }
        msgAvailable should be (false)
    }

    def checkTranslate(msgs: Seq[Any], as: JList[FlowAction]): Unit =
        msgs should contain(TranslateActions)

    def checkExecPacket(msgs: Seq[Any], as: JList[FlowAction]): Unit =
        msgs should contain(ExecPacket)

    def checkEmptyActions(msgs: Seq[Any], as: JList[FlowAction]): Unit =
        as should be (empty)

    def checkOutputActions(msgs: Seq[Any], as: JList[FlowAction]): Unit =
        as should contain (output)

    val applyEmptyActions = List[Check](checkEmptyActions, checkExecPacket)
    val applyOutputActions = List[Check](checkOutputActions, checkExecPacket)
           */
    class TestablePacketWorkflow(cookieGen: CookieGenerator,
                                 dpChannel: DatapathChannel,
                                 packetOut: Int => Unit,
                                 override val simulationExpireMillis: Long)
            extends PacketWorkflow(1,
                                   injector.getInstance(classOf[MidolmanConfig]),
                                   hostId, new DatapathStateDriver(new Datapath(0, "midonet")),
                                   cookieGen, clock, dpChannel,
                                   mockDhcpConfig,
                                   simBackChannel,
                                   flowProcessor,
                                   conntrackTable, natTable,
                                   new ShardedFlowStateTable[TraceKey, TraceContext](),
                                   peerResolver,
                                   Future.successful(new MockStateStorage()),
                                   HappyGoLuckyLeaser,
                                   metrics,
                                   new NullFlowRecorder(),
                                   packetOut) {
        var p = Promise[Any]()
        var generatedPacket: GeneratedPacket = _
        var nextActions: List[FlowAction] = _
        var exception: Exception = _

        def completeWithGenerated(actions: List[FlowAction],
                                  generatedPacket: GeneratedPacket): Unit = {
            this.generatedPacket = generatedPacket
            complete(actions)
        }

        def completeWithException(exception: Exception): Unit = {
            this.exception = exception
            complete(Nil)
        }

        def complete(actions: List[FlowAction]): Unit = {
            nextActions = actions
            p success null
        }

        def handlePackets(packets: Packet*): Unit = {
            packets foreach handlePacket
            process()
        }

        protected override def handleStateMessage(context: PacketContext): Unit =
            stateMessagesSeen += 1

        override def start(pktCtx: PacketContext) = {
            pktCtx.runs += 1
            pktCtx.addFlowTag(FlowTagger.tagForDpPort(1))
            pktCtx.addFlowRemovedCallback(new Callback0 {
                override def call(): Unit = { }
            })
            pktCtx.wcmatch.setSrcPort(pktCtx.wcmatch.getSrcPort + 1)
            if (nextActions ne null) {
                nextActions foreach pktCtx.addFlowAndPacketAction
            }
            if (pktCtx.runs == 1) {
                packetsSeen = packetsSeen :+ pktCtx
                if (pktCtx.isGenerated) {
                    FlowCreated
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
                } else if (exception ne null) {
                    throw exception
                }
                FlowCreated
            }
        }
    }
}
