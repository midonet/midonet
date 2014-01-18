/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.flows

import java.util.UUID
import scala.collection.immutable.List
import scala.collection.{Set => ROSet}
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.event.{NoLogging, LoggingAdapter}
import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{GivenWhenThen, Matchers, FeatureSpec}

import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.{DatapathState, FlowTranslator}
import org.midonet.midolman.{VirtualTopologyHelper, MockMidolmanActors}
import org.midonet.odp.DpPort
import org.midonet.odp.flows.FlowActions.userspace
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}

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

    private[this] def translate(actions: FlowAction*)
                               (prepareCtx: TranslationContext => Unit)
    : (WildcardFlow, ROSet[Any]) =
        translate(actions.toList)(prepareCtx)

    private[this] def translate(actions: List[FlowAction])
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

            def translate(actions: List[FlowAction]) = {
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
        def tunnelGre: Option[DpPort] = None
        def tunnelVxLan: Option[DpPort] = None
        def greOutputAction: Option[FlowActionOutput] = None
        def vxLanOutputAction: Option[FlowActionOutput] = None
        def getDpPortNumberForVport(vportId: UUID): Option[Integer] = None
        def getDpPortForInterface(itfName: String): Option[DpPort] = None
        def getVportForDpPortNumber(portNum: Integer): Option[UUID] = None
        def getDpPortName(num: Integer): Option[String] = None
    }
}
