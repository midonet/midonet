/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.{List => JList}
import scala.compat.Platform
import scala.collection.JavaConversions._
import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._
import akka.testkit._
import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.protos.{OvsDatapathConnection, MockOvsDatapathConnection}
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}

object PacketWorkflowTest {
    case object ExecPacket
    case object FlowCreated
    case object TranslateActions

    val NoLogging = Logger(NOPLogger.NOP_LOGGER)

    def forCookie(testKit: ActorRef, pkt: Packet, cookie: Int)
        (implicit system: ActorSystem): (PacketContext, PacketWorkflow) = {
        val dpCon = new MockOvsDatapathConnection(null)
        val flowListener = new MockOvsDatapathConnection.FlowListener() {
            override def flowDeleted(flow: Flow) {}
            override def flowCreated(flow: Flow) {}
        }
        val dpConPool = new DatapathConnectionPool() {
            override def getAll = List(dpCon).iterator
            override def get(hash: Int): OvsDatapathConnection = dpCon
            override def stop(): Unit = { }
            override def start(): Unit = { }
        }
        dpCon.flowsSubscribe(flowListener)
        val dpState = new DatapathStateManager(null)(null)
        val wcMatch = WildcardMatch.fromFlowMatch(pkt.getMatch)
        val pktCtx = new PacketContext(Left(cookie), pkt,
            Platform.currentTime + 10000, None, wcMatch)
        val wf = new PacketWorkflow(dpState, null, null, dpConPool,
                                    new ActionsCache(log = NoLogging), null) {
            override def runSimulation(pktCtx: PacketContext) =
                throw new Exception("no Coordinator")
            override def executePacket(pktCtx: PacketContext,
                                       actions: Seq[FlowAction]) = {
                testKit ! ExecPacket
                Future.successful(true)
            }
            override def translateActions(pktCtx: PacketContext,
                                          actions: Seq[FlowAction]) = {
                testKit ! TranslateActions
                Ready(Nil)
            }
            override def translateVirtualWildcardFlow(
                    pktCtx: PacketContext,
                    flow: WildcardFlow) = {
                testKit ! TranslateActions
                Ready(flow)
            }

            override def applyState(pktCtx: PacketContext,
                                    actions: Seq[FlowAction]): Unit = { }
        }
        (pktCtx, wf)
    }
}

@RunWith(classOf[JUnitRunner])
class PacketWorkflowTest extends TestKit(ActorSystem("PacketWorkflowTest"))
        with ImplicitSender with FeatureSpecLike with Matchers
        with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {

    import PacketWorkflow._
    import PacketWorkflowTest._
    import FlowController._

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

    feature("a PacketWorkflow queries the wildcard table to find matches") {

        scenario("Userspace-only tagged packets do not generate kernel flows") {
            Given("a PkWf object with a userspace Packet")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the PkWf finds a wildcardflow for the match")
            pkfw.handleWildcardTableMatch(pktCtx, wcFlow(true))

            Then("the Deduplication actor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw, applyOutputActions)
        }

        scenario("Non Userspace-only tagged packets generate kernel flows") {
            Given("a PkWf object with a userspace Packet")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the PkWf finds a wildcardflow for the match")
            pkfw.handleWildcardTableMatch(pktCtx, wcFlow(false))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a FlowAdded notification is sent to the Flow Controller")
            And("no wildcardflow are pushed to the Flow Controller")
            And("the resulting action is an OutputFlowAction")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw, applyOutputActionsWithFlow)
        }
    }

    feature("A PacketWorkflow handles results from the simulation layer") {

        scenario("A Simulation returns SendPacket") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns SendPacket")
            pkfw.processSimulationResult(pktCtx, Ready(SendPacket(List(output))))

            Then("action translation is performed")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw, checkTranslate, checkExecPacket)
        }

        scenario("A Simulation returns NoOp") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns NoOp")
            pkfw.processSimulationResult(pktCtx, Ready(NoOp))

            Then("the resulting actions are empty")
            runChecks(pktCtx, pkfw, checkEmptyActions _)
        }

        scenario("A Simulation returns Drop for userspace only match") {
            Given("a PkWf object with a userspace only match")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx, Ready(Drop))

            Then("the FlowController gets an AddWildcardFlow request")
            And("the packets gets executed (with 0 action)")
            runChecks(pktCtx, pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns Drop") {
            Given("a PkWf object with a match which is not userspace only")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx, Ready(Drop))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(pktCtx, pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns TemporaryDrop for userspace only match") {
            Given("a PkWf object with a userspace only match")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx,
                Ready(TemporaryDrop))

            Then("the FlowController gets an AddWildcardFlow request")
            And("the Deduplication actor gets an empty ApplyFlow request")
            And("the packets gets executed (with 0 action)")
            runChecks(pktCtx, pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns TemporaryDrop") {
            Given("a PkWf object with a match which is not userspace only")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop")
            pkfw.processSimulationResult(pktCtx,
                Ready(TemporaryDrop))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(pktCtx, pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow " +
                 "for userspace only match") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result =
                AddVirtualWildcardFlow(wcFlow(true))
            pkfw.processSimulationResult(pktCtx, Ready(result))

            Then("action translation is performed")
            And("the FlowController gets an AddWildcardFlow request")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw,
                checkTranslate _ :: checkAddWildcard _ :: applyOutputActions)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow") {
            Given("a PkWf object")
            val (pktCtx, pkfw) = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result = AddVirtualWildcardFlow(wcFlow())
            pkfw.processSimulationResult(pktCtx, Ready(result))

            Then("action translation is performed")
            And("the FlowController gets an AddWildcardFlow request")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(pktCtx, pkfw,
                checkTranslate _ :: checkAddWildcard _ :: applyOutputActions)
        }
    }

    /* helper generator functions */

    val output = FlowActions.output(12)

    def flMatch(userspace: Boolean = false) = {
        val flm = new FlowMatch()
        flm setUserSpaceOnly userspace
        flm
    }

    def packet(userspace: Boolean = false) =
        new Packet(Ethernet.random, flMatch(userspace))

    def wcMatch(userspace: Boolean = false) = {
        val wcMatch = WildcardMatch.fromFlowMatch(flMatch(userspace))
        if (userspace)
          wcMatch.getIcmpIdentifier
        wcMatch
    }

    def wcFlow(userspace: Boolean = false) =
        WildcardFlow(wcMatch(userspace), List(output))


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
        checks foreach {
            _(msgs, pw.actionsCache.actions.get(pktCtx.packet.getMatch)) }
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

    def checkAddWildcard(msgs: Seq[Any], as: JList[FlowAction]): Unit =
        msgs map { _.getClass } should contain(classOf[AddWildcardFlow])

    def checkFlowAdded(msgs: Seq[Any], as: JList[FlowAction]): Unit =
        msgs map { _.getClass } should contain(classOf[FlowAdded])

    val applyEmptyActions = List[Check](checkEmptyActions, checkExecPacket)

    val applyNilActionsWithFlow = checkFlowAdded _ :: applyEmptyActions

    val applyNilActionsWithWildcardFlow = checkAddWildcard _ :: applyEmptyActions

    val applyOutputActions = List[Check](checkOutputActions, checkExecPacket)

    val applyOutputActionsWithFlow = checkFlowAdded _ :: applyOutputActions

    val applyOutputActionsWithWildcardFlow =
        checkAddWildcard _ :: applyOutputActions
}
