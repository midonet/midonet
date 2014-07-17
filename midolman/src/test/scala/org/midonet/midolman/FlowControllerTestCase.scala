/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.{ArrayList, List => JList}

import scala.collection.JavaConversions._
import scala.util.Random

import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowKeys, FlowAction}
import org.midonet.odp.{FlowMatch, Flow}
import org.midonet.sdn.flows._
import org.midonet.util.functors.Callback0
import org.midonet.sdn.flows.FlowTagger.{TunnelKeyTag, FlowTag}

@RunWith(classOf[JUnitRunner])
class FlowControllerTestCase extends MidolmanSpec {

    registerActors(FlowController -> (() => new FlowController
                                            with MessageAccumulator))

    val flowTimeout: Int = 1000
    val tagCount: Int = 10

    def flowController = FlowController.as[FlowController
                                           with MessageAccumulator]

    feature("The flow controller initializes correctly") {
        scenario("The flow controller instance not null and metrics" +
                " initialized.") {
            When("When the flow controller initializes.")
            flowController should not be null

            Then("The wildcard flows metrics should be zero.")
            flowController.metrics.currentDpFlowsMetric.value should be(0)
            flowController.metrics.currentWildFlowsMetric.value should be(0)

            And("The flow manager should not be null.")
            flowController.flowManager should not be null

            And("The flow manager helper should not be null.")
            flowController.flowManagerHelper should not be null
        }
    }

    feature("Test the flow controller members.") {
        scenario("Test the invalidation history.") {
            Given("A list of tags of the history size plus delta.")

            // We invalidate a list of tags of at least history size to be
            // able to determine the oldest invalidation event (private member)
            val historySize = 1024
            val historyDelta = 1
            val tagCount = historySize + historyDelta
            val tags = Seq.fill(tagCount)(TestableFlow.getTag(0))

            When("Invalidate all tags.")
            for (tag <- 0 until tagCount) {
                FlowController ! FlowController.InvalidateFlowsByTag(tags(tag))
            }

            val youngest = FlowController.lastInvalidationEvent
            val oldest = youngest - historySize + 1

            Then("Tags valid for a negative lastSeen.")
            for (tag <- 0 until tagCount) {
                FlowController.isTagSetStillValid(-1,
                    Set(tags(tag))) should be (true)
            }

            And ("Tags not valid (EventSearchWindowMissed) for lastSeen in" +
                    " [0, oldest - 1)")
            for (lastSeen <- 0L until oldest - 1) {
                // 0 < tagCount
                FlowController.isTagSetStillValid(lastSeen,
                    Set(tags(0))) should be (false)
                FlowController.isTagSetStillValid(lastSeen,
                    Set(tags(tagCount - 1))) should be (false)
            }

            // Test 1
            And ("Tags valid (EventNotSeen) for lastSeen >= oldest - 1 and" +
                    " tags [0, historyDelta).")
            // Test 2
            And ("Tags not valid (EventSeen) for lastSeen >= oldest - 1 and" +
                    " tags [lastSeen - oldest + historySize + 1, historySize)")
            // Test 3
            And ("Tags valid (EventNotSeen) for lastSeen >= oldest - 1 and" +
                    " tags [historyDelta, lastSeen - oldest + historySize]")
            for (lastSeen <- oldest - 1 until tagCount) {
                // Test 1: condition 0 < historyDelta
                FlowController.isTagSetStillValid(lastSeen,
                    Set(tags(0))) should be (true)
                FlowController.isTagSetStillValid(lastSeen,
                    Set(tags(historyDelta - 1))) should be (true)
                // Test 2
                if ((lastSeen - oldest + historyDelta + 1).toInt < historySize) {
                    FlowController.isTagSetStillValid(lastSeen,
                        Set(tags((lastSeen - oldest + historyDelta + 1).toInt))
                    ) should be (false)
                    FlowController.isTagSetStillValid(lastSeen,
                        Set(tags(historySize - 1))) should be (false)
                }
                // Test 3
                if (historyDelta <= (lastSeen - oldest + historyDelta).toInt) {
                    FlowController.isTagSetStillValid(lastSeen,
                        Set(tags(historyDelta))) should be(true)
                    FlowController.isTagSetStillValid(lastSeen,
                        Set(tags((lastSeen - oldest + historyDelta).toInt))
                    ) should be (true)
                }
            }
        }
    }

    feature("The flow controller processes wildcard flows") {
        scenario("Addition and removal of a flow.") {

            Given("A wildcard flow.")
            val flow = new TestableFlow(1)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
                )

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow is removed from the flow controller.")
            FlowController ! FlowController.RemoveWildcardFlow(flow.wcMatch)

            testFlowRemoved(flow, mwcFlow, state)

            testMessages(Seq(
                classOf[FlowController.AddWildcardFlow],
                classOf[FlowController.RemoveWildcardFlow]))
        }

        scenario("Addition of a duplicate flow.") {

            Given("A wildcard flow.")
            val flow = new TestableFlow(2)

            val state = new MetricsSnapshot()

            When("The flow is added twice to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow was remove from the flow controller.")
            FlowController ! FlowController.RemoveWildcardFlow(flow.wcMatch)

            testFlowRemoved(flow, mwcFlow, state)

            testMessages(Seq(
                classOf[FlowController.AddWildcardFlow],
                classOf[FlowController.AddWildcardFlow],
                classOf[FlowController.RemoveWildcardFlow]))
        }

        scenario("Invalidate an existing flow by tag.") {
            Given("A wildcard flow.")
            val flow = new TestableFlow(3)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )

            val mwcFlow = testFlowAdded(flow, state)

            val tag = flow.getAnyTag

            When("The flow is invalidated by a tag.")
            FlowController ! FlowController.InvalidateFlowsByTag(tag)

            testFlowRemoved(flow, mwcFlow, state)

            And("The tag should appear in the invalidation history.")
            FlowController.isTagSetStillValid(1, flow.tagsSet) should be (false)

            testMessages(Seq(
                classOf[FlowController.AddWildcardFlow],
                classOf[FlowController.InvalidateFlowsByTag]))
        }

        scenario("Invalidate a non-existing tag.") {
            Given("A tag for a non-existing flow.")
            val tag = TestableFlow.getTag(4)

            Then("The tag should not appear in the tag to flows map.")
            flowController.tagToFlows.get(tag) should be (None)

            When("The flow is invalidated by a tag.")
            FlowController ! FlowController.InvalidateFlowsByTag(tag)

            Then("The tag should appear in the invalidation history.")
            FlowController.isTagSetStillValid(1, Set(tag)) should be(false)

            testMessages(Seq(
                classOf[FlowController.InvalidateFlowsByTag]))
        }

        scenario("Check idle expired flows are removed from the flow" +
                "controller") {
            Given("A wildcard flow.")
            val flow = new TestableFlow(5, TestableFlowIdleExpiration(),
                flowTimeout)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow has expired.")
            expireFlowIdle(mwcFlow)

            And("The flow controller checks the flow expiration.")
            FlowController ! FlowController.Internal.CheckFlowExpiration

            testFlowRemoved(flow, mwcFlow, state)

            testMessages(Seq(
                classOf[FlowController.AddWildcardFlow],
                FlowController.Internal.CheckFlowExpiration.getClass(),
                classOf[FlowController.Internal.FlowMissing]))
        }

        scenario("Check hard expired flows are removed from the flow" +
                " controller.") {
            Given("A wildcard flow.")
            val flow = new TestableFlow(6, TestableFlowHardExpiration(),
                flowTimeout)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow has expired.")
            expireFlowHard(mwcFlow)

            And("The flow controller checks the flow expiration.")
            FlowController ! FlowController.Internal.CheckFlowExpiration

            testFlowRemoved(flow, mwcFlow, state)

            testMessages(Seq(
                classOf[FlowController.AddWildcardFlow],
                FlowController.Internal.CheckFlowExpiration.getClass()))
        }

        scenario("Check non-expired flows are not removed from the flow" +
                "controller") {
            Given("A wildcard flow.")
            val flow = new TestableFlow(7, TestableFlowIdleExpiration(),
                flowTimeout)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )

            val mwcFlow = testFlowAdded(flow, state)

            When("The flow controller checks the flow expiration.")
            FlowController ! FlowController.Internal.CheckFlowExpiration

            testFlowExists(flow, mwcFlow, state)

            testMessages(Seq(
                classOf[FlowController.AddWildcardFlow],
                FlowController.Internal.CheckFlowExpiration.getClass()))
        }

        scenario("Check a datapath flow is removed via the flow manager helper.") {
            Given("A wildcard flow.")
            val flow = new TestableFlow(8)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )

            val mwcFlow = testFlowAdded(flow, state)

            flowController.flowManager.forgetFlow(flow.flowMatch)
            flowController.flowManagerHelper.removeFlow(flow.flowMatch)

            Then("The flow should not appear in the wildcard flow table.")
            FlowController.queryWildcardFlowTable(flow.wcMatch) should not be
                    (None)

            And("The datapath flow metric should be set at the original value.")
            flowController.metrics.currentDpFlowsMetric.value() should be (
                state.dpFlowsCount)

            And("The wildcard flow metric should be set at the original value.")
            flowController.metrics.currentWildFlowsMetric.value() should be (
                state.wcFlowsCount + 1)

            And("The flow manager should indicate the same number of flows.")
            flowController.metrics.currentDpFlowsMetric.value() should be (
                flowController.flowManager.getNumDpFlows())
            flowController.metrics.currentWildFlowsMetric.value() should be (
                flowController.flowManager.getNumWildcardFlows())

            And("The flow controller should contain the flow tag mapping tags.")
            for (tag <- flow.tagsSet) {
                flowController.tagToFlows.get(tag) match {
                    case None =>
                    case Some(set) => set should contain (mwcFlow)
                }
            }

            And("The flow removal callback method should not have been called.")
            flow.isFlowRemoved should be (false)

            testMessages(Seq(classOf[FlowController.AddWildcardFlow]))
        }

        scenario("Check a wildcard flow is removed via the flow manager helper.") {
            Given("A wildcard flow.")
            val flow = new TestableFlow(9)

            val state = new MetricsSnapshot()

            When("The flow is added to the flow controller.")
            FlowController ! FlowController.AddWildcardFlow(
                flow.wcFlow,
                flow.dpFlow,
                flow.callbacks,
                flow.tagsSet
            )

            val mwcFlow = testFlowAdded(flow, state)

            flowController.flowManagerHelper.removeWildcardFlow(mwcFlow)

            testFlowRemoved(flow, mwcFlow, state)

            testMessages(Seq(classOf[FlowController.AddWildcardFlow]))
        }
    }

    private def testFlowAdded(flow: TestableFlow,
                              state: MetricsSnapshot): ManagedWildcardFlow = {
        Then("The flow should appear in the wildcard flow table.")
        FlowController.queryWildcardFlowTable(flow.wcMatch) should not be
                (None)

        val mwcFlow = FlowController.queryWildcardFlowTable(flow.wcMatch) get

        And("The datapath flow metric should be incremented by one.")
        flowController.metrics.dpFlowsMetric.count() should be (
            state.dpFlowsCount + 1)
        flowController.metrics.currentDpFlowsMetric.value() should be (
            state.dpFlowsCount + 1)

        And("The wildcard flow metric should be incremented by one.")
        flowController.metrics.wildFlowsMetric.count() should be (
            state.wcFlowsCount + 1)
        flowController.metrics.currentWildFlowsMetric.value() should be (
            state.wcFlowsCount + 1)

        And("The flow manager should indicate the same number of flows.")
        flowController.metrics.currentDpFlowsMetric.value() should be (
            flowController.flowManager.getNumDpFlows())
        flowController.metrics.currentWildFlowsMetric.value() should be (
            flowController.flowManager.getNumWildcardFlows())

        And("The flow controller contains the correct tag mappings.")
        for (tag <- flow.tagsSet) {
            flowController.tagToFlows.get(tag) should not be (None)
            flowController.tagToFlows.get(tag).get should contain (mwcFlow)
        }

        return mwcFlow
    }

    private def testFlowRemoved(flow: TestableFlow,
                                mwcFlow: ManagedWildcardFlow,
                                state: MetricsSnapshot) {
        Then("The flow should not appear in the wildcard flow table.")
        FlowController.queryWildcardFlowTable(flow.wcMatch) should be(None)

        And("The datapath flow metric should be set at the original value.")
        flowController.metrics.currentDpFlowsMetric.value() should be (
            state.dpFlowsCount)

        And("The wildcard flow metric should be set at the original value.")
        flowController.metrics.currentWildFlowsMetric.value() should be (
            state.wcFlowsCount)

        And("The flow manager should indicate the same number of flows.")
        flowController.metrics.currentDpFlowsMetric.value() should be (
            flowController.flowManager.getNumDpFlows())
        flowController.metrics.currentWildFlowsMetric.value() should be (
            flowController.flowManager.getNumWildcardFlows())

        And("The flow controller should not contain the flow tag mapping tags.")
        for (tag <- flow.tagsSet) {
            flowController.tagToFlows.get(tag) match {
                case None =>
                case Some(set) => set should not contain (mwcFlow)
            }
        }

        And("The flow removal callback method was called.")
        flow.isFlowRemoved should be (true)
    }

    private def testFlowExists(flow: TestableFlow,
                               mwcFlow: ManagedWildcardFlow,
                               state: MetricsSnapshot) {
        Then("The flow should appear in the wildcard flow table.")
        FlowController.queryWildcardFlowTable(flow.wcMatch).get should be (
            mwcFlow)

        And("The datapath flow metric should be incremented by one.")
        flowController.metrics.currentDpFlowsMetric.value() should be (
            state.dpFlowsCount + 1)

        And("The wildcard flow metric should be incremented by one.")
        flowController.metrics.currentWildFlowsMetric.value() should be (
            state.wcFlowsCount + 1)

        And("The flow manager should indicate the same number of flows.")
        flowController.metrics.currentDpFlowsMetric.value() should be (
            flowController.flowManager.getNumDpFlows())
        flowController.metrics.currentWildFlowsMetric.value() should be (
            flowController.flowManager.getNumWildcardFlows())

        And("The flow controller contains the correct tag mappings.")
        for (tag <- flow.tagsSet) {
            flowController.tagToFlows.get(tag) should not be (None)
            flowController.tagToFlows.get(tag).get should contain (mwcFlow)
        }

        And("The flow removal callback method was not called.")
        flow.isFlowRemoved should be (false)
    }

    private def testMessages(messages: Seq[Class[_]]) {
        And("The expected list of received messages should equal the expected.")
        messages.length should be (FlowController.messages.length)

        for ((expected, message) <- (messages zip FlowController.messages)) {
            message.getClass should be (expected)
        }
    }

    private def expireFlowHard(mwcFlow: ManagedWildcardFlow) {
        mwcFlow.setCreationTimeMillis(System.currentTimeMillis() - flowTimeout)
    }

    private def expireFlowIdle(mwcFlow: ManagedWildcardFlow) {
        mwcFlow.setLastUsedTimeMillis(System.currentTimeMillis() - flowTimeout)
    }

    sealed abstract class TestableFlowType
    case class TestableFlowNoExpiration() extends TestableFlowType
    case class TestableFlowIdleExpiration() extends TestableFlowType
    case class TestableFlowHardExpiration() extends TestableFlowType

    sealed class TestableFlow(key: Int,
                              flowType: TestableFlowType = TestableFlowNoExpiration(),
                              expirationMillis: Int = -1) {
        private var flowRemoved = false
        private val tunnelId = (key.toLong << 32) |
                (Random.nextInt & 0xFFFFFFFFL)
        private val srcIpv4Address = (key << 16) | (Random.nextInt & 0xFFFF)
        private val dstIpv4Address = (key << 16) | (Random.nextInt & 0xFFFF)
        private val tags = Seq.fill(tagCount)(TestableFlow.getTag(key))

        val flowMatch = new FlowMatch().addKey(
            FlowKeys.tunnel(tunnelId, srcIpv4Address, dstIpv4Address))

        val wcMatch = WildcardMatch.fromFlowMatch(flowMatch)

        val wcFlow = flowType match {
            case TestableFlowNoExpiration() =>
                WildcardFlowFactory.create(wcMatch)
            case TestableFlowIdleExpiration() =>
                WildcardFlowFactory.createIdleExpiration(wcMatch, expirationMillis)
            case TestableFlowHardExpiration() =>
                WildcardFlowFactory.createHardExpiration(wcMatch, expirationMillis)
        }

        val dpFlow = new Flow(flowMatch, wcFlow.actions)

        val tagsSet = tags.toSet

        val callbacks = new ArrayList[Callback0]() { add(new Callback0 {
            def call() {
                flowRemoved = true
            }
        })}

        def isFlowRemoved = flowRemoved

        def getAnyTag: FlowTag = tags(Random.nextInt(tags.length))
    }

    sealed class MetricsSnapshot {
        val dpFlowsCount = flowController.metrics.currentDpFlowsMetric.value()
        val wcFlowsCount = flowController.metrics.currentWildFlowsMetric.value()
    }

    object TestableFlow {
        def getTag(key: Int): FlowTag = TunnelKeyTag(
            (key.toLong << 32) | (Random.nextInt & 0xFFFFFFFFL))
    }
}
