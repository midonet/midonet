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

import akka.actor.Actor
import akka.testkit.{TestActorRef, TestProbe}

import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.insights.Insights
import org.midonet.midolman.flows.{FlowExpirationIndexer, ManagedFlow}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.FlowMatch
import org.midonet.util.functors.Callback0

@RunWith(classOf[JUnitRunner])
class FlowControllerTest extends MidolmanSpec {
    val flowTimeout: Int = 1000
    val tagCount: Int = 10

    var flowController: FlowController = _

    override def beforeTest(): Unit =
        flowController = TestActorRef(new {
            val workerId = 0
            val flowProcessor = FlowControllerTest.this.flowProcessor
            val flowInvalidator = FlowControllerTest.this.simBackChannel
            val config = FlowControllerTest.this.config
            val metrics = FlowControllerTest.this.metrics
            val clock = FlowControllerTest.this.clock
            val datapathId = 0
            implicit val system = FlowControllerTest.this.actorSystem
            val actor = TestProbe()(system).ref
            val insights = Insights.NONE
            val preallocation = new MockFlowTablePreallocation(config)
        } with FlowController with Actor { def receive: Receive = { case _ => } }).underlyingActor

    feature("The flow controller processes flows") {
        scenario("A flow is added") {
            Given("A new flow")
            val flow = new TestableFlow()

            When("The flow is added to the flow controller")
            val managedFlow = flow.add()

            Then("The flow is registered and the metrics updated")
            managedFlow should not be null
            flowController.metrics.dpFlowsMetric.getCount should be (1)
        }

        scenario("A flow is removed") {
            Given("A flow in the flow controller")
            val flow = new TestableFlow()
            val managedFlow = flow.add()
            managedFlow should not be null
            flowController.metrics.dpFlowsMetric.getCount should be (1)

            When("The flow is removed from the flow controller")
            flow.remove(managedFlow)

            Then("The datapath flow metric should be set at the original value")
            flowController.metrics.currentDpFlowsMetric.getValue should be (0)

            And("The flow removal callback method was called")
            flow.callbackCalled should be (true)
        }

        scenario("The linked flow of a duplicate flow is removed") {
            Given("A flow in the flow controller")
            var flow = new TestableFlow(linked = new FlowMatch)
            var managedFlow = flow.add()
            managedFlow should not be null

            When("Marking one of the flows as duplicate")
            flowController.duplicateFlow(managedFlow.mark)

            Then("Both flows should be marked as removed")
            flow.callbackCalled should be (true)
            flow.linkedCallbackCalled should be (true)

            When("Marking the other flow as duplicate")
            flow = new TestableFlow(linked = new FlowMatch)
            managedFlow = flow.add()
            managedFlow should not be null
            flowController.duplicateFlow(managedFlow.linkedFlow.mark)

            Then("Both flows should be marked as removed")
            flow.callbackCalled should be (true)
            flow.linkedCallbackCalled should be (true)
        }

        scenario("both flows are removed if marked as duplicate") {
            Given("2 linked flows in the flow controller")
            val flow = new TestableFlow(linked = new FlowMatch)
            val managedFlow = flow.add()
            managedFlow should not be null

            val flow2 = new TestableFlow(linked = new FlowMatch)
            val managedFlow2 = flow2.add()
            managedFlow2 should not be null
            managedFlow.linkedFlow = managedFlow2

            When("one of the flows is removed from the flow controller")
            flow.remove(managedFlow)

            Then("both flows should be removed")
            managedFlow.removed should be (true)
            managedFlow2.removed should be (true)
        }

        scenario("A removed flow is not removed again") {
            Given("A flow in the flow controller")
            val flow = new TestableFlow()
            val managedFlow = flow.add()
            managedFlow should not be null
            flowController.metrics.dpFlowsMetric.getCount should be (1)

            // The flow is referenced by the FC and the expiration indexer
            managedFlow.currentRefCount should be (2)

            When("The flow is removed from the flow controller")
            flow.remove(managedFlow)

            Then("The datapath flow metric should be set at the original value")
            flowController.metrics.currentDpFlowsMetric.getValue should be (0)

            And("The flow removal callback method was called")
            flow.callbackCalled should be (true)
            flow.callbackCalled = false

            And("The flow was removed")
            managedFlow.removed should be (true)

            And("The flow is referenced by the in-progress deletion")
            managedFlow.currentRefCount should be (2)

            When("We expire the flow")
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value + 1
            flowController.checkFlowsExpiration(clock.tick)

            Then("Nothing should happen")
            flowController.metrics.currentDpFlowsMetric.getValue should be (0)
            flow.callbackCalled should be (false)
            managedFlow.removed should be (true)
            managedFlow.currentRefCount should be (1)
        }
    }

    final class TestableFlow(val fmatch: FlowMatch = new FlowMatch(),
                             val linked: FlowMatch = null) {
        var callbackCalled = false
        var linkedCallbackCalled = false

        def add(tags: FlowTag*): ManagedFlow = {
            val context = PacketContext.generated(0, null, fmatch)
            tags foreach context.addFlowTag
            context addFlowRemovedCallback new Callback0 {
                def call() = callbackCalled = true
            }
            if (linked ne null) {
                context.recircPayload = new Ethernet()
                context.recircMatch = linked
            }
            flowController.addFlow(context, FlowExpirationIndexer.FLOW_EXPIRATION)
            val flow = context.flow
            if (flow.linkedFlow ne null) {
                flow.linkedFlow.callbacks.add(new Callback0 {
                    def call() = linkedCallbackCalled = true
                })
            }
            flow
        }

        def remove(flow: ManagedFlow): Unit =
            flowController.removeFlow(flow)
    }
}
