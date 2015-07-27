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
import org.midonet.sdn.flows.FlowTagger.FlowTag

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.flows.{ManagedFlow, FlowExpirationIndexer}
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
             val id = 0
             val flowProcessor = FlowControllerTest.this.flowProcessor
             val flowInvalidator = FlowControllerTest.this.flowInvalidator
             val config = FlowControllerTest.this.config
             val metrics = FlowControllerTest.this.metrics
             val clock = FlowControllerTest.this.clock
             val datapathId = 0
             implicit val system = FlowControllerTest.this.actorSystem
             val log = Logger(NOPLogger.NOP_LOGGER)
             val actor = TestProbe()(system).ref
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
            flow.flowRemoved should be (true)
        }
    }

    final class TestableFlow(val fmatch: FlowMatch = new FlowMatch()) {
        var flowRemoved = false

        def add(tags: FlowTag*): ManagedFlow = {
            val context = new PacketContext(0, null, fmatch)
            tags foreach context.addFlowTag
            context addFlowRemovedCallback new Callback0 {
                def call() = flowRemoved = true
            }
            flowController.addFlow(context, FlowExpirationIndexer.FLOW_EXPIRATION)
            context.flow
        }

        def remove(flow: ManagedFlow): Unit =
            flowController.removeFlow(flow)
    }
}
