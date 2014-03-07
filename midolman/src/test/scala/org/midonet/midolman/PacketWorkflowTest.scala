/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.UUID
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Random
import scala.util.Success

import akka.actor._
import akka.pattern.AskableActorRef
import akka.testkit._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.protos.MockOvsDatapathConnection
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}

object PacketWorkflowTest {

    import PacketWorkflow._

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
        new PacketWorkflow(
                testKit, dpCon, dpState, null, null, pkt, wcMatch, Left(cookie), None) {
            def runSimulation() =
                Future.failed(new Exception("no Coordinator"))
            override def executePacket(actions: Seq[FlowAction]) = {
                testKit ! ExecPacket
                Future.successful(true)
            }
            override def translateActions(actions: Seq[FlowAction],
                                          inPortUUID: Option[UUID],
                                          dpTags: Option[mutable.Set[Any]],
                                          wMatch: WildcardMatch) = {
                testKit ! TranslateActions
                Future.successful(Nil)
            }
            override def translateVirtualWildcardFlow(
                    flow: WildcardFlow,
                    tags: scala.collection.Set[Any] = Set.empty) = {
                testKit ! TranslateActions
                Future.successful((flow, tags))
            }
            override def areTagsValid(tags: scala.collection.Set[Any]) =
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
    import DeduplicationActor._
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
            runChecks(applyOutputActions)
        }

        scenario("Non Userspace-only tagged packets generate kernel flows") {
            Given("a PkWf object with a userspace Packet")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the PkWf finds a wildcardflow for the match")
            pkfw.handleWildcardTableMatch(wcFlow(false))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a FlowAdded notification is sent to the Flow Controller")
            And("no wildcardflow are pushed to the Flow Controller")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(applyOutputActionsWithFlow)
        }
    }

    feature("A PacketWorkflow handles results from the simulation layer") {

        scenario("A Simulation returns SendPacket") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns SendPacket")
            pkfw.processSimulationResult(SendPacket(output))

            Then("action translation is performed")
            And("the current packet gets executed")
            runChecks(List(checkTranslate, checkExecPacket))
        }

        scenario("A Simulation returns NoOp") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(), cookie)

            When("the simulation layer returns NoOp")
            pkfw.processSimulationResult(NoOp)

            Then("the Deduplication actor gets an empty ApplyFlow request")
            runChecks(List(checkApplyNilFlow))
        }

        scenario("A Simulation returns Drop, tags are invalid") {
            Given("a PkWf object")
            val pkfw =
                PacketWorkflowTest.forCookie(self, packet(false), cookie, false)

            When("the simulation layer returns Drop and tags are invalid")
            pkfw.processSimulationResult(Drop(Set.empty,Set.empty))

            Then("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed (with 0 actions)")
            And("no wildcard are added.")
            runChecks(applyNilActions)
        }

        scenario("A Simulation returns Drop for userspace only match," +
                 " tags are valid") {
            Given("a PkWf object with a userspace only match")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(Drop(Set.empty,Set.empty))

            Then("the FlowController gets an AddWildcardFlow request")
            And("the Deduplication actor gets an empty ApplyFlow request")
            And("the packets gets executed (with 0 action)")
            runChecks(applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns Drop, tags are valid") {
            Given("a PkWf object with a match which is not userspace only")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(Drop(Set.empty,Set.empty))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns TemporaryDrop, tags are invalid") {
            Given("a PkWf object")
            val pkfw =
                PacketWorkflowTest.forCookie(self, packet(false), cookie, false)

            When("the simulation layer returns Drop and tags are invalid")
            pkfw.processSimulationResult(TemporaryDrop(Set.empty,Set.empty))

            Then("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed (with 0 actions)")
            And("no wildcard are added.")
            runChecks(applyNilActions)
        }

        scenario("A Simulation returns TemporaryDrop for userspace only match,"+
                 " tags are valid") {
            Given("a PkWf object with a userspace only match")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(TemporaryDrop(Set.empty,Set.empty))

            Then("the FlowController gets an AddWildcardFlow request")
            And("the Deduplication actor gets an empty ApplyFlow request")
            And("the packets gets executed (with 0 action)")
            runChecks(applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns TemporaryDrop, tags are valid") {
            Given("a PkWf object with a match which is not userspace only")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns Drop and tags are valid")
            pkfw.processSimulationResult(TemporaryDrop(Set.empty,Set.empty))

            Then("a FlowCreate request is made and processed by the Dp")
            And("a new WildcardFlow is sent to the Flow Controller")
            And("the current packet gets executed (with 0 actions)")
            runChecks(applyNilActionsWithWildcardFlow)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow, " +
                 "tags are invalid") {
            Given("a PkWf object")
            val pkfw =
                PacketWorkflowTest.forCookie(self, packet(false), cookie, false)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result = AddVirtualWildcardFlow(wcFlow(), Set.empty, Set.empty)
            pkfw.processSimulationResult(result)

            Then("action translation is performed")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("no wildcard are added.")
            And("the current packet gets executed")
            runChecks(checkTranslate _ :: applyOutputActions)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow " +
                 "for userspace only match, tags are valid") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(true), cookie)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result =
                AddVirtualWildcardFlow(wcFlow(true), Set.empty, Set.empty)
            pkfw.processSimulationResult(result)

            Then("action translation is performed")
            And("the FlowController gets an AddWildcardFlow request")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(
                checkTranslate _ :: checkAddWildcard _ :: applyRoutedOutputActions)
        }

        scenario("A Simulation returns AddVirtualWildcardFlow, " +
                 "tags are valid") {
            Given("a PkWf object")
            val pkfw = PacketWorkflowTest.forCookie(self, packet(false), cookie)

            When("the simulation layer returns AddVirtualWildcardFlow")
            val result = AddVirtualWildcardFlow(wcFlow(), Set.empty, Set.empty)
            pkfw.processSimulationResult(result)

            Then("action translation is performed")
            And("the FlowController gets an AddWildcardFlow request")
            And("the DeduplicationActor gets an ApplyFlow request")
            And("the current packet gets executed")
            runChecks(
                checkTranslate _ :: checkAddWildcard _ :: applyRoutedOutputActions)
        }
    }

    /* helper generator functions */

    val output = List(FlowActions.output(12))

    def flMatch(userspace: Boolean = false) = {
        val flm = new FlowMatch()
        flm setKeys new java.util.ArrayList[FlowKey]()
        flm setUserSpaceOnly userspace
        flm
    }

    def packet(userspace: Boolean = false) = {
        val pkt = new Packet
        pkt setPacket Ethernet.random
        pkt setMatch (flMatch(userspace))
        pkt
    }

    def wcMatch(userspace: Boolean = false) =
        WildcardMatch.fromFlowMatch(flMatch(userspace))

    def wcFlow(userspace: Boolean = false) =
        WildcardFlow(wcMatch(userspace), output)


    /* helpers for checking received msgs */

    type Check = Seq[Any] => Unit

    def runChecks(checks: List[Check]) {
        val msgs = receiveN(checks.size)
        checks.foreach{ _.apply(msgs) }
        msgAvailable should be(false)
    }

    def checkTranslate(msgs: Seq[Any]) =
        msgs should contain(TranslateActions)

    def checkExecPacket(msgs: Seq[Any]) =
        msgs should contain(ExecPacket)

    def checkApplyNilFlow(msgs: Seq[Any]) =
        msgs should contain(ApplyFlow(Nil, cookieOpt))

    def checkRoutedApplyNilFlow(msgs: Seq[Any]) =
        msgs should contain(ApplyFlowFor(ApplyFlow(Nil, cookieOpt), self))

    def checkApplyOutputFlow(msgs: Seq[Any]) =
        msgs should contain(ApplyFlow(output, cookieOpt))

    def checkRoutedApplyOutputFlow(msgs: Seq[Any]) =
        msgs should contain(ApplyFlowFor(ApplyFlow(output, cookieOpt), self))

    def checkAddWildcard(msgs: Seq[Any]) =
        msgs map { _.getClass } should contain(classOf[AddWildcardFlow])

    def checkFlowAdded(msgs: Seq[Any]) =
        msgs map { _.getClass } should contain(classOf[FlowAdded])

    val applyNilActions = List[Check](checkApplyNilFlow, checkExecPacket)

    val applyRoutedNilActions = List[Check](checkRoutedApplyNilFlow, checkExecPacket)

    val applyNilActionsWithFlow = checkFlowAdded _ :: applyNilActions

    val applyNilActionsWithWildcardFlow = checkAddWildcard _ :: applyRoutedNilActions

    val applyOutputActions = List[Check](checkApplyOutputFlow, checkExecPacket)

    val applyRoutedOutputActions = List[Check](checkRoutedApplyOutputFlow, checkExecPacket)

    val applyOutputActionsWithFlow = checkFlowAdded _ :: applyOutputActions

    val applyOutputActionsWithWildcardFlow = checkAddWildcard _ :: applyOutputActions

}
