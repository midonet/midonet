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

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.flows.{ManagedFlowImpl, FlowTagIndexer}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class FlowTagIndexerTest extends MidolmanSpec {

    val flowInvalidation = new FlowTagIndexer

    val tag1 = FlowTagger.tagForDpPort(1)
    val tag2 = FlowTagger.tagForDpPort(2)

    feature ("Flows are invalidated by tags") {
        scenario ("A flow is removed when a tag is invalidated") {
            val flow = new ManagedFlowImpl(null)
            flow.tags.add(tag1)
            flowInvalidation.indexFlowTags(flow)
            val invalid = flowInvalidation.invalidateFlowsFor(tag1).toStream
            invalid should contain theSameElementsAs List(flow)
        }

        scenario ("A flow can have multiple tags") {
            val flow = new ManagedFlowImpl(null)
            flow.tags.add(tag1)
            flow.tags.add(tag2)
            flowInvalidation.indexFlowTags(flow)
            val invalid = flowInvalidation.invalidateFlowsFor(tag1).toStream
            invalid should contain theSameElementsAs List(flow)

            val invalid2 = flowInvalidation.invalidateFlowsFor(tag2).toStream
            invalid2 should be (empty)

            flowInvalidation.flowsFor(tag1) should be (null)
            flowInvalidation.flowsFor(tag2) should be (null)
        }

        scenario ("Multiple flows can be invalidated") {
            val flow1 = new ManagedFlowImpl(null)
            flow1.tags.add(tag1)
            val flow2 = new ManagedFlowImpl(null)
            flow2.tags.add(tag1)
            flow2.tags.add(tag2)

            flowInvalidation.indexFlowTags(flow1)
            flowInvalidation.indexFlowTags(flow2)

            val invalid = flowInvalidation.invalidateFlowsFor(tag1).toStream
            invalid should contain theSameElementsAs List(flow1, flow2)

            val invalid2 = flowInvalidation.invalidateFlowsFor(tag2).toStream
            invalid2 should be (empty)

            flowInvalidation.flowsFor(tag1) should be (null)
            flowInvalidation.flowsFor(tag2) should be (null)
        }
    }

    feature ("Flows can be removed") {
        scenario ("A flow is removed from the tag lists") {
            val flow1 = new ManagedFlowImpl(null)
            flow1.tags.add(tag1)
            flow1.tags.add(tag2)
            flowInvalidation.indexFlowTags(flow1)
            val flow2 = new ManagedFlowImpl(null)
            flow2.tags.add(tag1)
            flow2.tags.add(tag2)

            flowInvalidation.flowsFor(tag1) should contain
                theSameElementsAs (List(flow1, flow2))
            flowInvalidation.flowsFor(tag2) should contain
                theSameElementsAs (List(flow1, flow2))

            flowInvalidation.removeFlowTags(flow1)

            flowInvalidation.flowsFor(tag1) should contain
                theSameElementsAs (List(flow2))
            flowInvalidation.flowsFor(tag2) should contain
                theSameElementsAs (List(flow2))
        }

        scenario ("A tag is removed when it contains no more flows") {
            val flow = new ManagedFlowImpl(null)
            flow.tags.add(tag1)
            flowInvalidation.indexFlowTags(flow)

            flowInvalidation.flowsFor(tag1) should contain
                theSameElementsAs (List(flow))

            flowInvalidation.removeFlowTags(flow)

            flowInvalidation.flowsFor(tag1) should be (null)
        }

    }
}
