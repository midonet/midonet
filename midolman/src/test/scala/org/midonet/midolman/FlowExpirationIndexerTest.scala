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

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.{ManagedFlowImpl, FlowExpirationIndexer}
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class FlowExpirationIndexerTest extends MidolmanSpec {

    val preallocation = new MockFlowTablePreallocation(MidolmanConfig.forTests) {
        override val maxFlows = 4
    }
    val flowExpiration = new FlowExpirationIndexer(preallocation)

    feature ("Flows are expired with a hard timeout") {

        scenario ("A flow is removed upon a hard timeout") {
            val flow = createFlow(FlowExpirationIndexer.FLOW_EXPIRATION)
            flowExpiration.enqueueFlowExpiration(flow)
            flow.currentRefCount should be (2)
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value - 1
            flowExpiration.pollForExpired(clock.tick) shouldBe null
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value
            flowExpiration.pollForExpired(clock.tick) shouldBe flow
            flowExpiration.pollForExpired(clock.tick) shouldBe null
            flow.currentRefCount should be (1)
        }

        scenario ("The removal of a flow is delayed") {
            val flow = createFlow(FlowExpirationIndexer.FLOW_EXPIRATION)
            flowExpiration.enqueueFlowExpiration(flow)
            flow.currentRefCount should be (2)
            flow.unref()
            flow.currentRefCount should be (1)

            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value + 1
            flowExpiration.pollForExpired(clock.tick) shouldBe flow
            flowExpiration.pollForExpired(clock.tick) shouldBe null
            flow.currentRefCount should be (0)
        }

        scenario ("There are multiple expiration types") {
            flowExpiration.enqueueFlowExpiration(createFlow(FlowExpirationIndexer.ERROR_CONDITION_EXPIRATION))
            flowExpiration.enqueueFlowExpiration(createFlow(FlowExpirationIndexer.FLOW_EXPIRATION))
            flowExpiration.enqueueFlowExpiration(createFlow(FlowExpirationIndexer.STATEFUL_FLOW_EXPIRATION))
            flowExpiration.enqueueFlowExpiration(createFlow(FlowExpirationIndexer.TUNNEL_FLOW_EXPIRATION))
            clock.time = Long.MaxValue
            flowExpiration.pollForExpired(clock.tick) should not be (null)
            flowExpiration.pollForExpired(clock.tick) should not be (null)
            flowExpiration.pollForExpired(clock.tick) should not be (null)
            flowExpiration.pollForExpired(clock.tick) should not be (null)
            flowExpiration.pollForExpired(clock.tick) shouldBe null
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
            flows foreach flowExpiration.enqueueFlowExpiration
            flowExpiration.pollForExpired(clock.tick) shouldBe flows(0)
            flowExpiration.pollForExpired(clock.tick) shouldBe flows(1)
            flowExpiration.pollForExpired(clock.tick) shouldBe null
        }
    }

    private def createFlow(exp: FlowExpirationIndexer.Expiration) = {
        val flow = new ManagedFlowImpl(null)
        flow.ref()
        flow.absoluteExpirationNanos = exp.value
        flow.expirationType = exp.typeId
        flow
    }
}
