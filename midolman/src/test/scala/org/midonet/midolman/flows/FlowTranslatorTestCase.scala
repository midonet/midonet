/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.flows

import java.util.UUID

import scala.collection.{Set => ROSet}
import scala.collection.immutable.List
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.event.{NoLogging, LoggingAdapter}
import akka.util.Timeout

import org.junit.runner.RunWith

import org.scalatest.{GivenWhenThen, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.{DatapathState, FlowTranslator}
import org.midonet.midolman.{VirtualTopologyHelper, MockMidolmanActors}
import org.midonet.odp.Port
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.odp.flows.FlowActions.userspace
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.rcu.Host

@RunWith(classOf[JUnitRunner])
class FlowTranslatorTestCase extends FeatureSpec
                             with Matchers
                             with GivenWhenThen
                             with MockMidolmanActors
                             with VirtualTopologyHelper {

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
                                       with MessageAccumulator))

    class TranslationContext(dpState: TestDatapathState) {
        def uplinkPid = dpState.uplinkPid

        def uplinkPid_=(pid: Int): this.type = {
            dpState.uplinkPid = pid
            this
        }
    }

    feature("UserspaceFlowActions are translated") {
        scenario("the uplink pid is correctly set") {
            Given("a UserspaceFlowAction")
            val ufa = userspace()

            When("translating that flow")
            val (wcflow, _) = translate(ufa) { ctx =>
                ctx.uplinkPid = 1
            }

            Then("the uplink pid should have been translated")
            wcflow.actions should be (List(userspace(1)))
        }
    }

    private[this] def translate(actions: FlowAction[_]*)
                               (prepareCtx: TranslationContext => Unit)
    : (WildcardFlow, ROSet[Any]) =
        translate(actions.toList)(prepareCtx)

    private[this] def translate(actions: List[FlowAction[_]])
                               (prepareCtx: TranslationContext => Unit)
    : (WildcardFlow, ROSet[Any]) = {
        val testDpState = new TestDatapathState
        val ctx = new TranslationContext(testDpState)

        prepareCtx(ctx)

        val wcmatch = new WildcardMatch()
        val f = new FlowTranslator {
            implicit protected def system: ActorSystem = actorSystem
            implicit protected val requestReplyTimeout: Timeout = Timeout(3 seconds)
            val log: LoggingAdapter = NoLogging
            val cookieStr = ""
            val dpState = testDpState

            def translate(actions: List[FlowAction[_]]) = {
                val wcflow = WildcardFlow(wcmatch, actions = actions)
                translateVirtualWildcardFlow(wcflow, null)
            }
        }.translate(actions)

        Await.result(f, 3 seconds)
    }

    class TestDatapathState extends DatapathState {
        var version: Long = 0
        var host: Host = null
        var uplinkPid: Int = 0

        def peerTunnelInfo(peer: UUID): Option[(Int, Int)] = None
        def tunnelGre: Option[Port[_, _]] = None
        def greOutputAction: Option[FlowActionOutput] = None
        def getDpPortNumberForVport(vportId: UUID): Option[Integer] = None
        def getDpPortForInterface(itfName: String): Option[Port[_, _]] = None
        def getVportForDpPortNumber(portNum: Integer): Option[UUID] = None
        def getDpPortName(num: Integer): Option[String] = None
    }
}
