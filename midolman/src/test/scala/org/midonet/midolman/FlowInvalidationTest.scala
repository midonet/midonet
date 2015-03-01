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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.flows.{FlowInvalidation, FlowLifecycle}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.{FlowTagger, ManagedFlow}

@RunWith(classOf[JUnitRunner])
class FlowInvalidationTest extends MidolmanSpec {

    class FlowAddRemover(flowsRemoved: Queue[ManagedFlow]) extends FlowLifecycle {
        val log = Logger(NOPLogger.NOP_LOGGER)

        override def flowRemoved(flow: ManagedFlow): Unit =
            flowsRemoved += flow
    }

    val removedFlows = Queue[ManagedFlow]()
    val flowInvalidation = new FlowAddRemover(removedFlows) with FlowInvalidation

    val tag1 = FlowTagger.tagForDpPort(1)
    val tag2 = FlowTagger.tagForDpPort(2)

    feature ("Flows are invalidated by tags") {
        scenario ("A flow is removed when a tag is invalidated") {
            val flow = new ManagedFlow(null)
            flow.tags.add(tag1)
            flowInvalidation.registerFlow(flow)
            flowInvalidation.invalidateFlowsFor(tag1)

            removedFlows should contain theSameElementsAs List(flow)
        }

        scenario ("A flow can have multiple tags") {
            val flow = new ManagedFlow(null)
            flow.tags.add(tag1)
            flow.tags.add(tag2)
            flowInvalidation.registerFlow(flow)
            flowInvalidation.invalidateFlowsFor(tag1)

            removedFlows should contain theSameElementsAs List(flow)
            removedFlows.clear()

            flowInvalidation.invalidateFlowsFor(tag2)
            removedFlows should be (empty)
        }

        scenario ("Multiple flows can be invalidated") {
            val flow1 = new ManagedFlow(null)
            flow1.tags.add(tag1)
            val flow2 = new ManagedFlow(null)
            flow2.tags.add(tag1)
            flow2.tags.add(tag2)

            flowInvalidation.registerFlow(flow1)
            flowInvalidation.registerFlow(flow2)

            flowInvalidation.invalidateFlowsFor(tag1)

            removedFlows should contain theSameElementsAs List(flow1, flow2)
            removedFlows.clear()

            flowInvalidation.invalidateFlowsFor(tag2)
            removedFlows should be (empty)
        }
    }
}
