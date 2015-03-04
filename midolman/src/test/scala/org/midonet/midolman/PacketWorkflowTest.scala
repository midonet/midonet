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

import java.util.{ArrayList, HashSet => JHashSet, List => JList, UUID}

import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._
import akka.testkit._
import com.typesafe.scalalogging.Logger
import org.slf4j.helpers.NOPLogger
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import datapath.DatapathChannel
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.state.{HappyGoLuckyLeaser, MockFlowStateTable, FlowStateReplicator}
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman.util.mock.MockDatapathChannel
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.functors.Callback0

object PacketWorkflowTest {
    case object ExecPacket
    case object FlowCreated
    case object TranslateActions

    val config = MidolmanConfig.forTests

    val NoLogging = Logger(NOPLogger.NOP_LOGGER)

    val output = FlowActions.output(12)

    val conntrackTable = new MockFlowStateTable[ConnTrackKey, ConnTrackValue]()
    val natTable = new MockFlowStateTable[NatKey, NatBinding]()
    val traceTable = new MockFlowStateTable[TraceKey, TraceContext]()

    var statePushed = false

    def forCookie(testKit: ActorRef, pkt: Packet, cookie: Int)
        (implicit system: ActorSystem): (PacketContext, PacketWorkflow) = {
        val dpChannel = new MockDatapathChannel() {
            override def executePacket(packet: Packet,
                                       actions: JList[FlowAction]): Unit = {
                testKit ! ExecPacket
                Future.successful(true)
            }
        }
        val dpState = new DatapathStateManager(null)(null, null)
        val wcMatch = pkt.getMatch
        val pktCtx = new PacketContext(cookie, pkt, wcMatch)
        pktCtx.callbackExecutor = CallbackExecutor.Immediate
        pktCtx.initialize(new FlowStateTransaction(conntrackTable),
                          new FlowStateTransaction(natTable),
                          HappyGoLuckyLeaser,
                          new FlowStateTransaction(traceTable))

        val replicator = new FlowStateReplicator(null, null, null, null, new UnderlayResolver {
            override def host: ResolvedHost = new ResolvedHost(UUID.randomUUID(), true, Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[Route] = ???
            override def vtepTunnellingOutputAction: FlowActionOutput = ???
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = ???
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = ???
        }, null, 0) {
            override def pushState(dpChannel: DatapathChannel): Unit = {
                 statePushed = true
            }

            override def accumulateNewKeys(
                          conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                          natTx: FlowStateTransaction[NatKey, NatBinding],
                          traceTx: FlowStateTransaction[TraceKey, TraceContext],
                          ingressPort: UUID, egressPorts: JList[UUID],
                          tags: JHashSet[FlowTag],
                          callbacks: ArrayList[Callback0]): Unit = { }
        }
        val wf = new PacketWorkflow(dpState, null, null, dpChannel,
                                    replicator, config) {
            override def runSimulation(pktCtx: PacketContext) =
                throw new Exception("no Coordinator")
            override def translateActions(pktCtx: PacketContext) = {
                testKit ! TranslateActions
                pktCtx.addFlowAndPacketAction(output)
            }
        }
        (pktCtx, wf)
    }
}

@RunWith(classOf[JUnitRunner])
class PacketWorkflowTest extends TestKit(ActorSystem("PacketWorkflowTest"))
        with ImplicitSender with FeatureSpecLike with Matchers
        with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {

    import FlowController._
    import PacketWorkflow._
    import PacketWorkflowTest._

    val cookie = 42
    val cookieOpt = Some(cookie)

    /* ensures the testKit is clean before running a test */
    before { while (msgAvailable) receiveOne(0 seconds) }

    val testKit = self
    val aliases = List(PacketsEntryPoint.Name, FlowController.Name)

    override def afterAll() { system.shutdown() }

    def makeAlias(name: String)(implicit ctx: ActorContext) {
        ctx.actorOf(Props(new Forwarder), name)
    }

    class Forwarder extends Actor {
        def receive = { case m => testKit forward m }
    }

    class ForwarderParent extends Actor {
        override def preStart() { aliases foreach makeAlias }
        def receive = { case _ => }
    }

    system.actorOf(Props(new ForwarderParent), "midolman")

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
}
