/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.UUID
import java.util.{List => JList}
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._
import akka.event.NoLogging
import akka.testkit._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.protos.MockOvsDatapathConnection
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.FlowTagger.FlowTag

object PacketWorkflowTest {
    case object ExecPacket
    case object FlowCreated
    case object TranslateActions

    def forCookie(testKit: ActorRef, pkt: Packet,
                  cookie: Int, tagsValid: Boolean = true)
        (implicit system: ActorSystem) = {
        val dpCon = new MockOvsDatapathConnection(null)
        val flowListener = new MockOvsDatapathConnection.FlowListener() {
            override def flowDeleted(flow: Flow) {}
            override def flowCreated(flow: Flow) {}
        }
        dpCon.flowsSubscribe(flowListener)
        val dpState = new DatapathStateManager(null)(null)
        val wcMatch = WildcardMatch.fromFlowMatch(pkt.getMatch)
        new PacketWorkflow(dpCon, dpState, null, null,
                           new ActionsCache(log = NoLogging),
                           pkt, wcMatch, Left(cookie), None) {
            def runSimulation() =
                throw new Exception("no Coordinator")
            override def executePacket(actions: Seq[FlowAction]) = {
                testKit ! ExecPacket
                Future.successful(true)
            }
            override def translateActions(actions: Seq[FlowAction],
                                          inPortUUID: Option[UUID],
                                          dpTags: mutable.Set[FlowTag],
                                          wMatch: WildcardMatch) = {
                testKit ! TranslateActions
                Ready(Nil)
            }
            override def translateVirtualWildcardFlow(
                    flow: WildcardFlow,
                    tags: scala.collection.Set[FlowTag]) = {
                testKit ! TranslateActions
                Ready((flow, tags))
            }
            override def areTagsValid(tags: scala.collection.Set[FlowTag]) =
                tagsValid
        }
    }
}

@RunWith(classOf[JUnitRunner])
class PacketWorkflowTest extends TestKit(ActorSystem("PacketWorkflowTest"))
        with ImplicitSender with FeatureSpecLike with Matchers
        with GivenWhenThen with BeforeAndAfter {

    import PacketWorkflow._
    import PacketWorkflowTest._
    import FlowController._

    val cookie = 42
    val cookieOpt = Some(cookie)

    /* ensures the testKit is clean before running a test */
    before { while (msgAvailable) receiveOne(0 seconds) }

    val testKit = self
    val aliases = List(PacketsEntryPoint.Name, FlowController.Name)

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
            val pkfw = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the PkWf finds a wildcardflow for the match")
            pkfw.handleWildcardTableMatch(wcFlow(true))

            Then("the Deduplication actor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(pkfw, applyOutputActions)
        }

        scenario("Non Userspace-only tagged packets generate kernel flows") {
            Given("a PkWf object with a userspace Packet")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the PkWf finds a wildcardflow for the match")
            pkfw.handleWildcardTableMatch(wcFlow(false))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a FlowAdded notification is sent to the Flow Controller")
            And("no wildcardflow are pushed to the Flow Controller")
            And("the resulting action is an OutputFlowAction")
            And("the current packet gets executed")
            runChecks(pkfw, applyOutputActionsWithFlow)
        }
    }

    feature("A PacketWorkflow handles results from the simulation layer") {

        scenario("A Simulation returns SendPacket") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns SendPacket")
            pkfw.processSimulationResult(Ready(SendPacket(List(output))))

            Then("action translation is performed")
            And("the current packet gets executed")
            runChecks(pkfw, checkTranslate, checkExecPacket)
        }

        scenario("A Simulation returns NoOp") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns NoOp")
            pkfw.processSimulationResult(Ready(NoOp))

            Then("the resulting actions are empty")
            runChecks(pkfw, checkEmptyActions _)
        }

        scenario("A Simulation returns Drop, tags are invalid") {
            Given("a PkWf object")
            val pkfw =
                PacketWorkflowTest.forCookie(self, packet(false), cookie, false)

            When("the simulation layer returns Drop and tags are invalid")
            pkfw.processSimulationResult(Ready(Drop(Set.empty, Nil)))

            Then("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed (with 0 actions)")
            And("no wildcard are added.")

            runChecks(pkfw, checkEmptyActions _)
        }

        scenario("A Simulation returns Drop for userspace only match," +
                 " tags are valid") {
            Given("a PkWf object with a userspace only match")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(Ready(Drop(Set.empty, Nil)))

            Then("the FlowController gets an AddWildcardFlow request")
            And("the packets gets executed (with 0 action)")
            runChecks(pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns Drop, tags are valid") {
            Given("a PkWf object with a match which is not userspace only")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(Ready(Drop(Set.empty, Nil)))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns TemporaryDrop, tags are invalid") {
            Given("a PkWf object")
            val pkfw =
                PacketWorkflowTest.forCookie(self, packet(false), cookie, false)

            When("the simulation layer returns Drop and tags are invalid")
            pkfw.processSimulationResult(
                Ready(TemporaryDrop(Set.empty, Nil)))

            Then("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed (with 0 actions)")
            And("no wildcard are added.")
            runChecks(pkfw, applyEmptyActions)
        }

        scenario("A Simulation returns TemporaryDrop for userspace only match,"+
                 " tags are valid") {
            Given("a PkWf object with a userspace only match")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(
                Ready(TemporaryDrop(Set.empty, Nil)))

            Then("the FlowController gets an AddWildcardFlow request")
            And("the Deduplication actor gets an empty ApplyFlow request")
            And("the packets gets executed (with 0 action)")
            runChecks(pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns TemporaryDrop, tags are valid") {
            Given("a PkWf object with a match which is not userspace only")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(
                Ready(TemporaryDrop(Set.empty, Nil)))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(pkfw, applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow, " +
                 "tags are invalid") {
            Given("a PkWf object")
            val pkfw =
                PacketWorkflowTest.forCookie(self, packet(false), cookie, false)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result = AddVirtualWildcardFlow(wcFlow(), Nil, Set.empty)
            pkfw.processSimulationResult(Ready(result))

            Then("action translation is performed")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("no wildcard are added.")
            And("the current packet gets executed")
            runChecks(pkfw, checkTranslate _ :: applyOutputActions)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow " +
                 "for userspace only match, tags are valid") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result =
                AddVirtualWildcardFlow(wcFlow(true), Nil, Set.empty)
            pkfw.processSimulationResult(Ready(result))

            Then("action translation is performed")
            And("the FlowController gets an AddWildcardFlow request")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(pkfw,
                checkTranslate _ :: checkAddWildcard _ :: applyOutputActions)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow, " +
                 "tags are valid") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result = AddVirtualWildcardFlow(wcFlow(), Nil, Set.empty)
            pkfw.processSimulationResult(Ready(result))

            Then("action translation is performed")
            And("the FlowController gets an AddWildcardFlow request")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(pkfw,
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

    def runChecks(pw: PacketWorkflow, checks: List[Check]) {
        runChecks(pw, checks:_*)
    }

    def runChecks(pw: PacketWorkflow, checks: Check*) {
        def drainMessages(): List[AnyRef] = {
            receiveOne(500 millis) match {
                case null => Nil
                case msg => msg :: drainMessages()
            }
        }
        val msgs = drainMessages()
        checks foreach {
            _(msgs, pw.actionsCache.actions.get(pw.packet.getMatch)) }
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
