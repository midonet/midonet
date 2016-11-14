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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.flows.ManagedFlow
import org.midonet.midolman.monitoring.NullFlowRecorder
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{HappyGoLuckyLeaser, MockFlowStateTable, FlowStateAgentPackets => FlowStatePackets}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.FlowKeys.tunnel
import org.midonet.odp.flows.{FlowAction, FlowActions}
import org.midonet.odp.ports.{GreTunnelPort, VxLanTunnelPort}
import org.midonet.odp.{Datapath, FlowMatches, Packet}
import org.midonet.packets.Ethernet
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.util.functors.Callback0

@RunWith(classOf[JUnitRunner])
class PacketWorkflowTest extends MidolmanSpec {

    private var packetsSeen = List[PacketContext]()
    private var packetWorkflow: TestablePacketWorkflow = _
    private var packetsOut = 0
    private var stateMessagesSeen = 0

    private val conntrackTable = new MockFlowStateTable[ConnTrackKey, ConnTrackValue]()
    private val natTable = new MockFlowStateTable[NatKey, NatBinding]()

    override def beforeTest() {
        createPacketWorkflow()
    }

    def createPacketWorkflow(simulationExpireMillis: Long = 5000L,
                             custom: Boolean = true,
                             overlayVxlanPort: Int = 10,
                             vtepVxlanPort: Int = 11,
                             vppVxlanPort: Int = 12): Unit = {
        val dpState = new DatapathStateDriver(new Datapath(0, "midonet"))
        dpState.tunnelOverlayGre = new GreTunnelPort("overlay-gre")
        dpState.tunnelOverlayVxLan = new VxLanTunnelPort("overlay-vxlan", 4700,
                                                         overlayVxlanPort)
        dpState.tunnelVtepVxLan = new VxLanTunnelPort("vtep-vxlan", 4701,
                                                      vtepVxlanPort)
        dpState.tunnelVppVxlan = new VxLanTunnelPort("vpp-vxlan", 4702,
                                                     vppVxlanPort)

        packetWorkflow = new TestablePacketWorkflow(new CookieGenerator(1, 1),
                                                    mockDpChannel,
                                                    dpState,
                                                    (x: Int) => { packetsOut += x },
                                                    custom = custom,
                                                    simulationExpireMillis)
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

    def makeStatePacket(): Packet = {
        val eth = FlowStatePackets.makeUdpShell(Array())
        val fmatch = FlowMatches.fromEthernetPacket(eth)
        fmatch.addKey(tunnel(FlowStatePackets.TUNNEL_KEY, 1, 2, 0))
        new Packet(eth, fmatch)
    }

    def makeUniquePacket(variation: Short): Packet = makeUniqueFrame(variation)

    def makeVppPacket(vni: Int): Packet = {
        makeFrame(5) << { vxlan vni vni }
    }

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
                List(output(1)), GeneratedLogicalPacket(id, frame, -1))
            packetWorkflow.process()

            Then("the generated packet should be simulated")
            packetsSeen map { _.ethernet } should be (List(pkt.getEthernet,
                                                           frame))

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
                List(), GeneratedPhysicalPacket(2, frame, -1))
            packetWorkflow.process()

            Then("the generated packet should be seen")
            packetsSeen map { _.ethernet } should be (List(pkt.getEthernet,
                                                           frame))

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
            packetWorkflow.flowActions = List(FlowActions.output(1))

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

        scenario("Processed context not restarted after postpone") {
            createPacketWorkflow(0)

            Given("A postponed simulation")
            val packet = makePacket(1)
            packetWorkflow.handlePackets(packet)

            When("Processes finishes")
            mockDpChannel.contextsSeen.asScala foreach { c =>
                c.setFlowProcessed()
                c.setPacketProcessed()
            }
            packetWorkflow.process()

            And("Completing the postpone future")
            packetWorkflow.p.trySuccess(null)

            Then("The workflow is not restarted")
            packetWorkflow.backChannel.hasMessages shouldBe false
        }

        scenario("Workflow handles VPP tunnel packets without matching port") {
            val vxlanPortNumber = 20
            createPacketWorkflow(0, custom = false,
                                 vppVxlanPort = vxlanPortNumber)

            Given("A VPP packet")
            val packet = makeVppPacket(1000)
            packet.getMatch.setInputPortNumber(vxlanPortNumber)
            packet.getMatch.setTunnelKey(1000)

            When("Simulating the packet")
            packetWorkflow.handlePackets(packet)

            Then("The packet should be processed")
            packetWorkflow.result shouldBe FlowCreated
            packetWorkflow.flowActions shouldBe empty
        }
    }

    feature("Packet Context pooling") {
        scenario("Successful contexts are returned to the pool") {
            Given("A successful simulation")
            val packet = makePacket(1)
            packetWorkflow.handlePackets(packet)
            packetWorkflow.complete(null)
            packetWorkflow.process()
            packetsOut should be (1)

            Then("1 context should have been allocated")
            metrics.contextsAllocated.getCount shouldBe 1
            metrics.contextsPooled.getCount shouldBe 0
            metrics.contextsBeingProcessed.getCount shouldBe 1

            When("the context is processed")
            mockDpChannel.contextsSeen.asScala foreach { c =>
                c.setFlowProcessed()
                c.setPacketProcessed()
            }
            packetWorkflow.process()

            Then("the context should be returned to the pool")
            metrics.contextsPooled.getCount shouldBe 1
            metrics.contextsBeingProcessed.getCount shouldBe 0
        }

        scenario("dropped contexts are returned to the pool") {
            Given("A failed simulation")
            val packet = makePacket(1)
            packetWorkflow.handlePackets(packet)
            packetWorkflow.completeWithException(new Exception("foobar"))
            packetWorkflow.process()

            When("the packet is dropped")
            isCleared(packetsSeen.head)
            packetsOut should be (1)

            Then("1 context should have been allocated, and sent to datapath")
            metrics.contextsAllocated.getCount shouldBe 1
            metrics.contextsPooled.getCount shouldBe 0
            metrics.contextsBeingProcessed.getCount shouldBe 1

            When("processing finishes")
            mockDpChannel.contextsSeen.asScala foreach  { c =>
                c.setFlowProcessed()
                c.setPacketProcessed()
            }
            packetWorkflow.process()

            Then("1 context should have been allocated and returned to pool")
            metrics.contextsAllocated.getCount shouldBe 1
            metrics.contextsPooled.getCount shouldBe 1
            metrics.contextsBeingProcessed.getCount shouldBe 0
        }

        scenario("expired postponed context are returned") {
            createPacketWorkflow(0)

            Given("A postponed simulation")
            val packet = makePacket(1)
            packetWorkflow.handlePackets(packet)

            Then("a context is allocated, should be timed out immediately")
            metrics.contextsAllocated.getCount shouldBe 1
            metrics.contextsPooled.getCount shouldBe 0
            metrics.contextsBeingProcessed.getCount shouldBe 1

            When("processing finishes")
            mockDpChannel.contextsSeen.asScala foreach { c =>
                c.setFlowProcessed()
                c.setPacketProcessed()
            }
            packetWorkflow.process()

            Then("the context should be returned to the pool")
            metrics.contextsAllocated.getCount shouldBe 1
            metrics.contextsPooled.getCount shouldBe 1
            metrics.contextsBeingProcessed.getCount shouldBe 0
        }

        scenario("Generated packets get returned to pool on drop") {
            Given("A successful simulation")
            val packet = makePacket(1)
            packetWorkflow.handlePackets(packet)

            Then("a context is allocated")
            metrics.contextsAllocated.getCount shouldBe 1
            metrics.contextsPooled.getCount shouldBe 0
            metrics.contextsBeingProcessed.getCount shouldBe 0

            When("Completed with a failing generated packet")
            val frame: Ethernet = makeFrame(1)
            packetWorkflow.completeWithFailingGenerated(
                List(), GeneratedPhysicalPacket(2, frame, -1),
                new Exception("fail generated"))
            packetWorkflow.process()

            Then("the original packet should be processed")
            metrics.contextsBeingProcessed.getCount shouldBe 1

            And("a context is allocated for the generated packet")
            metrics.contextsAllocated.getCount shouldBe 2

            And("and returned to the pool on failure")
            metrics.contextsPooled.getCount shouldBe 1

            When("the original packet finishes processing")
            mockDpChannel.contextsSeen.asScala foreach { c =>
                c.setFlowProcessed()
                c.setPacketProcessed()
            }
            packetWorkflow.process()

            Then("the pool should contain both allocated contexts")
            metrics.contextsAllocated.getCount shouldBe 2
            metrics.contextsPooled.getCount shouldBe 2
            metrics.contextsBeingProcessed.getCount shouldBe 0
        }

        scenario("Pool never exceeds size") {
            Given("More packets than there are pool places")
            val packets = 1.to(1200) map { i => makePacket(i.toShort) }

            When("the packets are simulated")
            packetWorkflow.handlePackets(packets:_*)
            packetWorkflow.complete(null)
            packetWorkflow.process()
            packetsOut should be (1200)

            Then("contexts should have been allocated, but not pooled")
            metrics.contextsAllocated.getCount shouldBe 1200
            metrics.contextsPooled.getCount shouldBe 0
            metrics.contextsBeingProcessed.getCount shouldBe 1200

            When("the contexts are processed")
            mockDpChannel.contextsSeen.asScala foreach { c =>
                c.setFlowProcessed()
                c.setPacketProcessed()
            }
            packetWorkflow.process()

            Then("the max number of contexts should be pooled")
            metrics.contextsPooled.getCount shouldBe 1024

            When("Another packet is sent")
            packetWorkflow.handlePackets(makePacket(2222))

            Then("No new contexts are allocated, there's one less pooled")
            metrics.contextsAllocated.getCount shouldBe 1200
            metrics.contextsPooled.getCount shouldBe 1023
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
                                 dpState: DatapathState,
                                 packetOut: Int => Unit,
                                 custom: Boolean,
                                 override val simulationExpireMillis: Long)
            extends PacketWorkflow(1, 0, injector.getInstance(classOf[MidolmanConfig]),
                                   hostId, dpState,
                                   cookieGen, clock, dpChannel,
                                   mockDhcpConfig,
                                   simBackChannel,
                                   flowProcessor,
                                   conntrackTable, natTable,
                                   new ShardedFlowStateTable[TraceKey, TraceContext](),
                                   peerResolver,
                                   HappyGoLuckyLeaser,
                                   metrics,
                                   NullFlowRecorder,
                                   injector.getInstance(classOf[VirtualTopology]),
                                   packetOut) {
        var p = Promise[Any]()
        var generatedPacket: GeneratedPacket = _
        var generatedException: Exception = _
        var flowActions: List[FlowAction] = _
        var exception: Exception = _
        var result: SimulationResult = _
        var flow: ManagedFlow = _

        def completeWithGenerated(actions: List[FlowAction],
                                  generatedPacket: GeneratedPacket): Unit = {
            this.generatedPacket = generatedPacket
            complete(actions)
        }

        def completeWithFailingGenerated(actions: List[FlowAction],
                                         generatedPacket: GeneratedPacket,
                                         exception: Exception): Unit = {
            this.generatedPacket = generatedPacket
            this.generatedException = exception
            complete(actions)
        }

        def completeWithException(exception: Exception): Unit = {
            this.exception = exception
            complete(Nil)
        }

        def complete(actions: List[FlowAction]): Unit = {
            flowActions = actions
            p success null
        }

        def handlePackets(packets: Packet*): Unit = {
            packets foreach handlePacket
            process()
        }

        protected override def handleStateMessage(context: PacketContext): Unit =
            stateMessagesSeen += 1

        override def start(pktCtx: PacketContext): SimulationResult = {
            if (!custom) {
                result = super.start(pktCtx)
                flow = pktCtx.flow
                flowActions = pktCtx.flowActions.asScala.toList
                return result
            }

            pktCtx.runs += 1
            pktCtx.addFlowTag(FlowTagger.tagForDpPort(1))
            pktCtx.addFlowRemovedCallback(new Callback0 {
                override def call(): Unit = { }
            })
            pktCtx.wcmatch.setSrcPort(pktCtx.wcmatch.getSrcPort + 1)
            if (flowActions ne null) {
                flowActions foreach pktCtx.flowActions.add
            }
            if (pktCtx.runs == 1) {
                packetsSeen = packetsSeen :+ pktCtx
                if (pktCtx.isGenerated) {
                    if (generatedException ne null) {
                        throw generatedException
                    } else FlowCreated
                } else {
                    throw NotYetException(p.future)
                }
            } else if (pktCtx.runs == 2) {
                // re-suspend the packet (with a completed future)
                throw NotYetException(p.future)
            } else {
                if (generatedPacket ne null) {
                    pktCtx.backChannel.tell(generatedPacket)
                    generatedPacket = null
                } else if (exception ne null) {
                    throw exception
                }
                FlowCreated
            }
        }
    }
}
