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

import java.util.{ArrayList, HashSet => JHashSet}

import scala.collection.JavaConversions._
import scala.util.Random

import org.junit.runner.RunWith
import org.midonet.midolman.flows.FlowInvalidation
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowActions, FlowKeys}
import org.midonet.odp.{Flow, FlowMatch}
import org.midonet.sdn.flows.FlowTagger.{FlowTag, TunnelKeyTag}
import org.midonet.sdn.flows._
import org.midonet.util.functors.Callback0

@RunWith(classOf[JUnitRunner])
class FlowControllerTest extends MidolmanSpec {

    registerActors(FlowController -> (() => new FlowController))

    val flowTimeout: Int = 1000
    val tagCount: Int = 10

    def flowController = FlowController.as[FlowController]

    feature("The flow controller initializes correctly") {
        scenario("The flow controller instance not null and metrics " +
                 "initialized") {
            When("When the flow controller initializes")
            flowController should not be null

            Then("The wildcard flows metrics should be zero")
            flowController.metrics.currentDpFlowsMetric.getValue should be(0)

            And("The flow manager should not be null")
            flowController.flowManager should not be null

            And("The flow manager helper should not be null")
            flowController.flowManagerHelper should not be null
        }
    }

    feature("The flow controller processes wildcard flows") {
        scenario("Addition and removal of a flow") {

            Given("A wildcard flow")
            val flow = new TestableFlow(1)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller")
            flow.add()

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow is removed from the flow controller")
            flow.remove()

            testFlowRemoved(flow, mwcFlow, state)
        }

        scenario("Addition of a duplicate flow") {

            Given("A wildcard flow")
            val flow = new TestableFlow(2)

            val state = new MetricsSnapshot()

            When("The flow is added twice to the flow controller")
            flow.add()
            flow.add()

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow was remove from the flow controller")
            flow.remove()

            testFlowRemoved(flow, mwcFlow, state)
        }

        scenario("Invalidate an existing flow by tag") {
            Given("A wildcard flow")
            val flow = new TestableFlow(3)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller")
            flow.add()

            val mwcFlow = testFlowAdded(flow, state)

            val tag = flow.getAnyTag

            When("The flow is invalidated by a tag")
            flowInvalidator.scheduleInvalidationFor(tag)

            testFlowRemoved(flow, mwcFlow, state)

            And("The tag should appear in the invalidation history")
            val pktCtx = new PacketContext(0, null, new FlowMatch)
            pktCtx.addFlowTag(tag)
            FlowInvalidation.isTagSetStillValid(pktCtx) should be (false)
        }

        scenario("Invalidate a non-existing tag") {
            Given("A tag for a non-existing flow")
            val tag = TestableFlow.getTag(4)

            When("The flow is invalidated by a tag")
            flowInvalidator.scheduleInvalidationFor(tag)

            Then("The tag should appear in the invalidation history")
            val pktCtx = new PacketContext(0, null, new FlowMatch)
            pktCtx.lastInvalidation = -1
            pktCtx.addFlowTag(tag)
            FlowInvalidation.isTagSetStillValid(pktCtx) should be (false)
        }

        scenario("Check idle expired flows are removed from the flow " +
                 "controller") {
            Given("A wildcard flow")
            val flow = new TestableFlow(5, flowTimeout)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller")
            flow.add()
            dpConn().flowsCreate(null, new Flow(flow.flowMatch))

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow has expired")
            expireFlowIdle(mwcFlow)

            And("The flow controller checks the flow expiration")
            FlowController ! FlowController.CheckFlowExpiration_

            testFlowRemoved(flow, mwcFlow, state)
        }

        scenario("Check hard expired flows are removed from the flow " +
                 "controller") {
            Given("A wildcard flow")
            val flow = new TestableFlow(6, flowTimeout, TestableFlowHardExpiration)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller")
            flow.add()

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow has expired")
            expireFlowHard(mwcFlow)

            And("The flow controller checks the flow expiration")
            FlowController ! FlowController.CheckFlowExpiration_

            testFlowRemoved(flow, mwcFlow, state)
        }

        scenario("Check non-expired flows are not removed from the flow " +
                 "controller") {
            Given("A wildcard flow")
            val flow = new TestableFlow(7, flowTimeout)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller")
            flow.add()

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow controller checks the flow expiration")
            FlowController ! FlowController.CheckFlowExpiration_

            testFlowExists(flow, mwcFlow, state)
        }

        scenario("Check a datapath flow is removed via the flow manager helper") {
            Given("A wildcard flow")
            val flow = new TestableFlow(8)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller")
            flow.add()

            val mwcFlow = testFlowAdded(flow, state)

            val managedFlow = flowController.flowManager.dpFlowTable.remove(flow.flowMatch)
            flowController.flowManagerHelper.removeFlow(managedFlow)

            Then("The datapath flow metric should be set at the original value")
            flowController.metrics.currentDpFlowsMetric.getValue should be (
                state.dpFlowsCount)

            And("The flow manager should indicate the same number of flows")
            flowController.metrics.currentDpFlowsMetric.getValue should be (
                flowController.flowManager.getNumDpFlows)

            And("The flow removal callback method should not have been called")
            flow.isFlowRemoved should be (false)
        }

        scenario("Check a wildcard flow is removed via the flow manager helper") {
            Given("A wildcard flow")
            val flow = new TestableFlow(9)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller")
            flow.add()

            val mwcFlow = testFlowAdded(flow, state)

            flowController.flowManagerHelper.removeWildcardFlow(mwcFlow)

            testFlowRemoved(flow, mwcFlow, state)
        }
    }

    private def testFlowAdded(flow: TestableFlow,
                              state: MetricsSnapshot): ManagedFlow = {
        Then("The datapath flow metric should be incremented by one")
        flowController.metrics.dpFlowsMetric.getCount should be (
            state.dpFlowsCount + 1)
        flowController.metrics.currentDpFlowsMetric.getValue should be (
            state.dpFlowsCount + 1)

        And("The flow manager should indicate the same number of flows")
        flowController.metrics.currentDpFlowsMetric.getValue should be (
            flowController.flowManager.getNumDpFlows)

        flowController.flowManager.dpFlowTable.get(flow.flowMatch)
    }

    private def testFlowRemoved(flow: TestableFlow,
                                mwcFlow: ManagedFlow,
                                state: MetricsSnapshot) {
        Then("The datapath flow metric should be set at the original value")
        flowController.metrics.currentDpFlowsMetric.getValue should be (
            state.dpFlowsCount)

        And("The flow manager should indicate the same number of flows")
        flowController.metrics.currentDpFlowsMetric.getValue should be (
            flowController.flowManager.getNumDpFlows)

        And("The flow removal callback method was called")
        flow.isFlowRemoved should be (true)
    }

    private def testFlowExists(flow: TestableFlow,
                               mwcFlow: ManagedFlow,
                               state: MetricsSnapshot) {
        Then("The datapath flow metric should be incremented by one")
        flowController.metrics.currentDpFlowsMetric.getValue should be (
            state.dpFlowsCount + 1)

        And("The flow manager should indicate the same number of flows")
        flowController.metrics.currentDpFlowsMetric.getValue should be (
            flowController.flowManager.getNumDpFlows())

        And("The flow removal callback method was not called")
        flow.isFlowRemoved should be (false)
    }

    private def expireFlowHard(mwcFlow: ManagedFlow) {
        mwcFlow.setCreationTimeMillis(System.currentTimeMillis() - flowTimeout)
    }

    private def expireFlowIdle(mwcFlow: ManagedFlow) {
        mwcFlow.setLastUsedTimeMillis(System.currentTimeMillis() - flowTimeout)
    }

    sealed abstract class TestableFlowType
    case object TestableFlowIdleExpiration extends TestableFlowType
    case object TestableFlowHardExpiration extends TestableFlowType

    sealed class TestableFlow(key: Int,
                              expirationMillis: Int = -1,
                              flowType: TestableFlowType = TestableFlowIdleExpiration) {
        private var flowRemoved = false
        private val tunnelId = (key.toLong << 32) |
                (Random.nextInt & 0xFFFFFFFFL)
        private val srcIpv4Address = (key << 16) | (Random.nextInt & 0xFFFF)
        private val dstIpv4Address = (key << 16) | (Random.nextInt & 0xFFFF)
        private val tags = Seq.fill(tagCount)(TestableFlow.getTag(key))

        val flowMatch = new FlowMatch().addKey(
            FlowKeys.tunnel(tunnelId, srcIpv4Address, dstIpv4Address, 0))

        val tagsSet = tags.foldLeft(new JHashSet[FlowTag])((s, x) => { s.add(x); s})

        val callbacks = new ArrayList[Callback0]() { add(new Callback0 {
            def call() {
                flowRemoved = true
            }
        })}

        def isFlowRemoved = flowRemoved

        def getAnyTag: FlowTag = tags(Random.nextInt(tags.length))

        def add(): Unit = {
            val pktCtx = new PacketContext(0, null, flowMatch)
            pktCtx.callbackExecutor = CallbackExecutor.Immediate
            pktCtx.lastInvalidation = FlowInvalidation.lastInvalidationEvent
            tags foreach pktCtx.addFlowTag
            callbacks foreach pktCtx.addFlowRemovedCallback
            flowType match {
                case TestableFlowIdleExpiration =>
                    pktCtx.idleExpirationMillis = expirationMillis
                case TestableFlowHardExpiration =>
                    pktCtx.hardExpirationMillis = expirationMillis
            }
            pktCtx.flowActions.add(FlowActions.output(4))
            FlowController ! pktCtx
        }

        def remove(): Unit =
            flowInvalidator.scheduleInvalidationFor(tags.head)
    }

    sealed class MetricsSnapshot {
        val dpFlowsCount = flowController.metrics.currentDpFlowsMetric.getValue
    }

    object TestableFlow {
        def getTag(key: Int): FlowTag = TunnelKeyTag(
            (key.toLong << 32) | (Random.nextInt & 0xFFFFFFFFL))
    }
}
