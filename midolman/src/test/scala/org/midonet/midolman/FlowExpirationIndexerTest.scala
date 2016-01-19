/*
 * Copyright 2015 Midokura SARL
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

import scala.collection.mutable.Queue

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.flows.{ManagedFlow, FlowExpirationIndexer, FlowIndexer}
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class FlowExpirationIndexerTest extends MidolmanSpec {

    class FlowAddRemover(flowsRemoved: Queue[ManagedFlow]) extends FlowIndexer {
        val log = Logger(NOPLogger.NOP_LOGGER)
        val workerId = 0
        val maxFlows = 4

        override def removeFlow(flow: ManagedFlow): Unit =
            flowsRemoved += flow
    }

    val removedFlows = Queue[ManagedFlow]()
    val flowExpiration = new FlowAddRemover(removedFlows) with FlowExpirationIndexer

    feature ("Flows are expired with a hard timeout") {

        scenario ("A flow is removed upon a hard timeout") {
            val flow = createFlow(FlowExpirationIndexer.FLOW_EXPIRATION)
            flowExpiration.registerFlow(flow)
            flow.currentRefCount should be (2)
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value - 1
            flowExpiration.checkFlowsExpiration(clock.tick)
            removedFlows should be (empty)
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value
            flowExpiration.checkFlowsExpiration(clock.tick)
            removedFlows should have size 1
            removedFlows.dequeue() should be (flow)
            flow.currentRefCount should be (1)
        }

        scenario ("The removal of a flow is delayed") {
            val flow = createFlow(FlowExpirationIndexer.FLOW_EXPIRATION)
            flowExpiration.registerFlow(flow)
            flow.currentRefCount should be (2)
            flowExpiration.removeFlow(flow)
            flow.unref()
            flow.currentRefCount should be (1)
            removedFlows.clear()
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value + 1
            flowExpiration.checkFlowsExpiration(clock.tick)
            removedFlows should have size 1
            removedFlows.dequeue() should be (flow)
            flow.currentRefCount should be (0)
        }

        scenario ("There are multiple expiration types") {
            flowExpiration.registerFlow(createFlow(FlowExpirationIndexer.ERROR_CONDITION_EXPIRATION))
            flowExpiration.registerFlow(createFlow(FlowExpirationIndexer.FLOW_EXPIRATION))
            flowExpiration.registerFlow(createFlow(FlowExpirationIndexer.STATEFUL_FLOW_EXPIRATION))
            flowExpiration.registerFlow(createFlow(FlowExpirationIndexer.TUNNEL_FLOW_EXPIRATION))
            clock.time = Long.MaxValue
            flowExpiration.checkFlowsExpiration(clock.tick)
            removedFlows should have size 4
        }
    }

    feature ("Oversubscription results in removal of excess flows") {

        scenario ("The oldest flows are removed") {
            val flows = List(
                createFlow(FlowExpirationIndexer.FLOW_EXPIRATION),
                createFlow(FlowExpirationIndexer.FLOW_EXPIRATION),
                createFlow(FlowExpirationIndexer.FLOW_EXPIRATION),
                createFlow(FlowExpirationIndexer.FLOW_EXPIRATION),
                createFlow(FlowExpirationIndexer.FLOW_EXPIRATION),
                createFlow(FlowExpirationIndexer.FLOW_EXPIRATION))
            flows foreach flowExpiration.registerFlow
            flowExpiration.checkFlowsExpiration(0)
            removedFlows should have size 2
            removedFlows.dequeue() should be (flows(0))
            removedFlows.dequeue() should be (flows(1))
        }
    }

    private def createFlow(exp: FlowExpirationIndexer.Expiration) = {
        val flow = new ManagedFlow(null)
        flow.ref()
        flow.absoluteExpirationNanos = exp.value
        flow.expirationType = exp.typeId
        flow
    }
}
