/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.midolman.flows

import java.util.Random

import scala.collection.mutable.{ArrayBuffer, Buffer, HashMap}
import scala.collection.JavaConverters._
import scala.util.Random.shuffle

import com.google.common.collect.Lists

import org.junit.runner.RunWith
import org.mockito.Matchers.{eq => mockEq}
import org.mockito.Mockito.verify
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.Util
import org.midonet.insights.Insights
import org.midonet.midolman.CallbackRegistry.{CallbackSpec, SerializableCallback}
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.{FlowController, MockFlowTablePreallocation, PacketWorkersService}
import org.midonet.odp.{Flow, FlowMatch, FlowMatches}
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class NativeFlowControllerTest extends MidolmanSpec with MockitoSugar {
    val flowTimeout: Int = 1000
    val tagCount: Int = 10

    var flowController: FlowController = _
    var mockMeterRegistry: MeterRegistry = _

    var callbackCalled = false
    var linkedCallbackCalled = false
    var callbackCalledSpec: CallbackSpec = _
    val random = new Random

    val tag1 = FlowTagger.tagForDpPort(1)
    val tag2 = FlowTagger.tagForDpPort(2)

    override def beforeTest(): Unit = {
        val preallocation = new MockFlowTablePreallocation(config)
        mockMeterRegistry = mock[MeterRegistry]
        flowController = new NativeFlowController(
            config, clock, flowProcessor,
            0, 0, metrics,
            mockMeterRegistry,
            cbRegistry, Insights.NONE)
        val callbackCalledCbId = cbRegistry.registerCallback(
            new SerializableCallback() {
                override def call(args: Array[Byte]): Unit = {
                    callbackCalled = true
                }
            })
        callbackCalledSpec = new CallbackSpec(callbackCalledCbId,
                                              new Array[Byte](0))
    }

    feature("The flow controller processes flows") {
        scenario("A flow is added") {
            When("The flow is added to the flow controller")
            val flowMatch = FlowMatches.generateFlowMatch(random)
            val tags = Lists.newArrayList[FlowTagger.FlowTag]()
            val managedFlow = flowController.addFlow(
                flowMatch,
                tags,
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)

            Then("The flow is registered and the metrics updated")
            managedFlow should not be null
            metrics.dpFlowsMetric.getCount should be (1)
            verify(mockMeterRegistry).trackFlow(mockEq(flowMatch),
                                                mockEq(tags))
        }

        scenario("A flow is removed") {
            Given("A flow in the flow controller")
            val managedFlow = flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            managedFlow should not be null
            metrics.dpFlowsMetric.getCount should be (1)

            When("The flow is removed from the flow controller")
            flowController.removeDuplicateFlow(managedFlow.mark)

            Then("The datapath flow metric should be set at the original value")
            metrics.currentDpFlowsMetric.getValue should be (0)

            And("The flow removal callback method was called")
            callbackCalled should be (true)
        }

        scenario("The linked flow of a duplicate flow is removed") {
            Given("A flow in the flow controller")
            val managedFlow = flowController.addRecircFlow(
                FlowMatches.generateFlowMatch(random),
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION).
                asInstanceOf[NativeFlowController#NativeManagedFlow]
            managedFlow should not be null

            val linkedFlowId = managedFlow.linkedId.toInt
            val flowId = managedFlow.id.toInt
            flowController.flowExists(linkedFlowId) shouldBe true
            flowController.flowExists(flowId) shouldBe true

            When("Marking one of the flows as duplicate")
            flowController.removeDuplicateFlow(managedFlow.mark)

            Then("Both flows should be marked as removed")
            callbackCalled shouldBe (true)
            flowController.flowExists(linkedFlowId) shouldBe false
            flowController.flowExists(flowId) shouldBe false

            When("Marking the other flow as duplicate")
            val managedFlow2 = flowController.addRecircFlow(
                FlowMatches.generateFlowMatch(random),
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION).
                asInstanceOf[NativeFlowController#NativeManagedFlow]
            managedFlow2 should not be null
            callbackCalled = false

            val linkedFlowId2 = managedFlow2.linkedId.toInt
            val flowId2 = managedFlow2.id.toInt
            flowController.flowExists(linkedFlowId2) shouldBe true
            flowController.flowExists(flowId2) shouldBe true

            flowController.removeDuplicateFlow(linkedFlowId2)

            Then("Both flows should be marked as removed")
            callbackCalled shouldBe (true)
            flowController.flowExists(linkedFlowId2) shouldBe false
            flowController.flowExists(flowId2) shouldBe false
        }

        scenario("Expiration removes a flow") {
            Given("A flow in the flow controller")
            val managedFlow = flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            managedFlow should not be null
            metrics.dpFlowsMetric.getCount should be (1)
            flowController.flowExists(managedFlow.mark) shouldBe true

            When("We expire the flow")
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value + 1
            flowController.process()

            Then("The datapath flow metric should be set at the original value")
            metrics.currentDpFlowsMetric.getValue should be (0)

            And("The flow removal callback method was called")
            callbackCalled should be (true)
            callbackCalled = false

            And("The flow was removed")
            flowController.flowExists(managedFlow.mark) shouldBe false
        }

        scenario("A removed flow is not removed again") {
            Given("A flow in the flow controller")
            val managedFlow =  flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            managedFlow should not be null
            metrics.dpFlowsMetric.getCount should be (1)

            // The flow is referenced by the FC
            flowController.flowExists(managedFlow.mark) shouldBe true

            When("The flow is removed from the flow controller")
            flowController.removeDuplicateFlow(managedFlow.mark)

            Then("The datapath flow metric should be set at the original value")
            metrics.currentDpFlowsMetric.getValue should be (0)

            And("The flow removal callback method was called")
            callbackCalled should be (true)
            callbackCalled = false

            And("The flow was removed")
            flowController.flowExists(managedFlow.mark) shouldBe false

            When("We expire the flow")
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value + 1
            flowController.process()

            Then("Nothing should happen, expiration doesn't keep refs")
            metrics.currentDpFlowsMetric.getValue should be (0)
            callbackCalled should be (false)
            flowController.flowExists(managedFlow.mark) shouldBe false
        }

        scenario("invalidation removes a flow") {
            Given("A flow in the flow controller")
            val managedFlow1 =  flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(tag1, tag2),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            managedFlow1 should not be null

            val managedFlow2 =  flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(tag1),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            managedFlow2 should not be null

            val managedFlow3 =  flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(tag2),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            managedFlow3 should not be null

            metrics.dpFlowsMetric.getCount should be (3)
            flowController.flowExists(managedFlow1.mark) shouldBe true
            flowController.flowExists(managedFlow2.mark) shouldBe true
            flowController.flowExists(managedFlow3.mark) shouldBe true

            When("a tag is invalidated")
            flowController.invalidateFlowsFor(tag1)

            Then("Two of the flows should be removed")
            metrics.currentDpFlowsMetric.getValue should be (1)
            flowController.flowExists(managedFlow1.mark) shouldBe false
            flowController.flowExists(managedFlow2.mark) shouldBe false
            flowController.flowExists(managedFlow3.mark) shouldBe true

            And("The flow removal callback method was called")
            callbackCalled should be (true)
            callbackCalled = false

            When("the other tag is invalidated")
            flowController.invalidateFlowsFor(tag2)

            Then("the last flow should be removed")
            metrics.currentDpFlowsMetric.getValue should be (0)
            flowController.flowExists(managedFlow1.mark) shouldBe false
            flowController.flowExists(managedFlow2.mark) shouldBe false
            flowController.flowExists(managedFlow3.mark) shouldBe false

            And("The flow removal callback method was called again")
            callbackCalled should be (true)
            callbackCalled = false
        }

        scenario("invalidation removes a flow (many flows)") {
            val tagsToInvalidate = ArrayBuffer.empty[FlowTagger.FlowTag]
            val flows = HashMap.empty[FlowMatch, Buffer[FlowTagger.FlowTag]]
            val numFlows = 10000

            Given("Many flows in the flow controller")
            for (i <- 1 to numFlows) {
                val flowMatch = FlowMatches.generateFlowMatch(random)
                val tag0 = FlowTagger.tagForDpPort(0)
                val tag1 = FlowTagger.tagForDpPort(i)
                val tag2 = FlowTagger.tagForDpPort(i / 2 + 100)
                val tags = List(tag0, tag1, tag2)
                val managedFlow = flowController.addFlow(
                    flowMatch,
                    Lists.newArrayList(shuffle(tags).asJava),
                    Lists.newArrayList(callbackCalledSpec),
                    FlowExpirationIndexer.FLOW_EXPIRATION)
                managedFlow should not be null
                flowsTable.put(flowMatch, new Flow())

                tagsToInvalidate += tag1
                tagsToInvalidate += tag2
                flows.getOrElseUpdate(flowMatch, ArrayBuffer.empty) ++= tags
            }

            metrics.dpFlowsMetric.getCount should be (numFlows)

            When("Tags are invalidated in a random order")
            for (tag <- shuffle(tagsToInvalidate.toList)) {
                flowController.invalidateFlowsFor(tag)
                flows.retain { (_, v) => !v.contains(tag) }
                val expected = flows.size
                flowsTable.size should be (expected)
                metrics.currentDpFlowsMetric.getValue should be (expected)
            }

            Then("All flows should be removed")
            flowsTable.size should be (0)
            metrics.currentDpFlowsMetric.getValue should be (0)
        }

        scenario("invalidation removes a linked flow") {
            Given("A flow in the flow controller")
            val managedFlow = flowController.addRecircFlow(
                FlowMatches.generateFlowMatch(random),
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(tag1),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION).
                asInstanceOf[NativeFlowController#NativeManagedFlow]
            managedFlow should not be null

            val linkedFlowId = managedFlow.linkedId.toInt
            val flowId = managedFlow.id.toInt
            flowController.flowExists(linkedFlowId) shouldBe true
            flowController.flowExists(flowId) shouldBe true

            When("invalidate the flow")
            flowController.invalidateFlowsFor(tag1)

            Then("Both flows should be marked as removed")
            callbackCalled shouldBe (true)
            flowController.flowExists(linkedFlowId) shouldBe false
            flowController.flowExists(flowId) shouldBe false
        }

        scenario("invalidated flow isn't removed again on expiration") {
            Given("A flow in the flow controller")
            val managedFlow =  flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(tag1),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            managedFlow should not be null
            metrics.dpFlowsMetric.getCount should be (1)

            // The flow is referenced by the FC
            flowController.flowExists(managedFlow.mark) shouldBe true

            When("The flow is invalidated")
            flowController.invalidateFlowsFor(tag1)

            Then("The datapath flow metric should be set at the original value")
            metrics.currentDpFlowsMetric.getValue should be (0)

            And("The flow removal callback method was called")
            callbackCalled should be (true)
            callbackCalled = false

            And("The flow was removed")
            flowController.flowExists(managedFlow.mark) shouldBe false

            When("We expire the flow")
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value + 1
            flowController.process()

            Then("Nothing should happen, expiration doesn't keep refs")
            metrics.currentDpFlowsMetric.getValue should be (0)
            callbackCalled should be (false)
            flowController.flowExists(managedFlow.mark) shouldBe false
        }
        scenario("Ensure space evicts a flow if table full") {
            Given("Two flows added to the flow controller")
            val nativeFlowController = flowController
                .asInstanceOf[NativeFlowController]
            val numWorkers = PacketWorkersService.numWorkers(config)
            val maxFlows = Math.min(
                Util.findNextPositivePowerOfTwo(
                    config.datapath.maxFlowCount / numWorkers),
                FlowController.IndexMask)
            val managedFlow1 =  flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(tag1),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)
            val managedFlow2 =  flowController.addFlow(
                FlowMatches.generateFlowMatch(random),
                Lists.newArrayList(tag1),
                Lists.newArrayList(callbackCalledSpec),
                FlowExpirationIndexer.FLOW_EXPIRATION)

            flowController.flowExists(managedFlow1.mark) shouldBe true
            flowController.flowExists(managedFlow2.mark) shouldBe true

            When("Ensuring there's space for maxFlows-1")
            nativeFlowController.ensureSpace(maxFlows-1)

            Then("The first flow should be evicted")
            flowController.flowExists(managedFlow1.mark) shouldBe false
            flowController.flowExists(managedFlow2.mark) shouldBe true

            When("Ensuring there's space for maxFlows")
            nativeFlowController.ensureSpace(maxFlows)
            Then("The other flow should be evicted and the table empty")
            flowController.flowExists(managedFlow2.mark) shouldBe false
        }
    }
}

