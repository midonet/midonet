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

import java.util.Random

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.{ManagedFlowImpl, FlowExpirationIndexer}
import org.midonet.midolman.flows.ManagedFlow.NoFlow
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class FlowExpirationIndexerTest extends MidolmanSpec {

    val preallocation = new MockFlowTablePreallocation(MidolmanConfig.forTests) {
        override val maxFlows = 4
    }
    val flowExpiration = new FlowExpirationIndexer(preallocation)
    val random = new Random

    feature ("Flows are expired with a hard timeout") {

        scenario ("A flow is removed upon a hard timeout") {
            val flow = createFlow(FlowExpirationIndexer.FLOW_EXPIRATION)
            flowExpiration.enqueueFlowExpiration(
                flow.id, flow.absoluteExpirationNanos, flow.expirationType)
            flow.currentRefCount should be (0)
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value - 1
            flowExpiration.pollForExpired(clock.tick) shouldBe NoFlow
            clock.time = FlowExpirationIndexer.FLOW_EXPIRATION.value
            flowExpiration.pollForExpired(clock.tick) shouldBe flow.id
            flowExpiration.pollForExpired(clock.tick) shouldBe NoFlow
            flow.currentRefCount should be (0)
        }

        scenario ("There are multiple expiration types") {
            val flow1 = createFlow(FlowExpirationIndexer.ERROR_CONDITION_EXPIRATION)
            val flow2 = createFlow(FlowExpirationIndexer.FLOW_EXPIRATION)
            val flow3 = createFlow(FlowExpirationIndexer.STATEFUL_FLOW_EXPIRATION)
            val flow4 = createFlow(FlowExpirationIndexer.TUNNEL_FLOW_EXPIRATION)
            flowExpiration.enqueueFlowExpiration(
                flow1.id, flow1.absoluteExpirationNanos, flow1.expirationType)
            flowExpiration.enqueueFlowExpiration(
                flow2.id, flow2.absoluteExpirationNanos, flow2.expirationType)
            flowExpiration.enqueueFlowExpiration(
                flow3.id, flow3.absoluteExpirationNanos, flow3.expirationType)
            flowExpiration.enqueueFlowExpiration(
                flow4.id, flow4.absoluteExpirationNanos, flow4.expirationType)

            clock.time = Long.MaxValue
            flowExpiration.pollForExpired(clock.tick) should not be (NoFlow)
            flowExpiration.pollForExpired(clock.tick) should not be (NoFlow)
            flowExpiration.pollForExpired(clock.tick) should not be (NoFlow)
            flowExpiration.pollForExpired(clock.tick) should not be (NoFlow)
            flowExpiration.pollForExpired(clock.tick) shouldBe NoFlow
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
            flows foreach { f =>
                flowExpiration.enqueueFlowExpiration(
                    f.id, f.absoluteExpirationNanos, f.expirationType)
            }
            flowExpiration.pollForExpired(clock.tick) shouldBe flows(0).id
            flowExpiration.pollForExpired(clock.tick) shouldBe flows(1).id
            flowExpiration.pollForExpired(clock.tick) shouldBe NoFlow
        }
    }

    private def createFlow(exp: FlowExpirationIndexer.Expiration) = {
        val flow = new ManagedFlowImpl(null)
        flow.absoluteExpirationNanos = exp.value
        flow.expirationType = exp.typeId
        flow.setId(Math.abs(random.nextLong))
        flow
    }
}
