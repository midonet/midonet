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

package org.midonet.midolman.flows

import java.util.ArrayDeque

import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.state.FlowState

object FlowExpiration {
    sealed abstract class Expiration {
        def value: Long
        val typeId: Int
    }
    object ERROR_CONDITION_EXPIRATION extends Expiration {
        val value = (5 seconds).toNanos
        val typeId = 0
    }
    object FLOW_EXPIRATION extends Expiration {
        var value = (1 minutes).toNanos
        val typeId = 1
    }
    object STATEFUL_FLOW_EXPIRATION extends Expiration {
        val value = FlowState.DEFAULT_EXPIRATION.toNanos / 2
        val typeId = 2
    }
    object TUNNEL_FLOW_EXPIRATION extends Expiration {
        def value = FLOW_EXPIRATION.value * 5
        val typeId = 3
    }

    private final val maxType = 4
}

/**
 * This trait deals with flow expiration. It registers all new flows and removes
 * them when the specified expiration time has elapsed. Note that a flow may
 * be removed from the kernel via another mechanism (such as flow invalidation),
 * but it is still kept in these data structures until it expires. This is to
 * avoid linear remove operations or smarter, more expensive data structures.
 */
trait FlowExpiration extends FlowLifecycle {
    import FlowExpiration._

    val log: Logger
    protected val maxFlows: Int

    private val expirationQueues = new Array[ArrayDeque[ManagedFlow]](maxType)

    {
        expirationQueues(ERROR_CONDITION_EXPIRATION.typeId) = new ArrayDeque(maxFlows / 3)
        expirationQueues(FLOW_EXPIRATION.typeId) = new ArrayDeque(maxFlows)
        expirationQueues(STATEFUL_FLOW_EXPIRATION.typeId) = new ArrayDeque(maxFlows)
        expirationQueues(TUNNEL_FLOW_EXPIRATION.typeId) = new ArrayDeque(maxFlows / 3)
    }

    abstract override def registerFlow(flow: ManagedFlow): Unit = {
        super.registerFlow(flow)
        expirationQueues(flow.expirationType).addLast(flow)
        flow.ref()
    }

    def checkFlowsExpiration(now: Long): Unit = {
        checkHardTimeOutExpiration(now)
        manageFlowTableSize()
    }

    private def checkHardTimeOutExpiration(now: Long): Unit = {
        var i = 0
        while (i < maxType) {
            var flow: ManagedFlow = null
            val queue = expirationQueues(i)
            while (({ flow = queue.peekFirst(); flow } ne null) &&
                   now >= flow.absoluteExpirationNanos) {
                log.debug(s"Removing flow $flow for hard expiration")
                maybeRemoveFlow(queue.pollFirst())
            }
            i += 1
        }
    }

    private def manageFlowTableSize(): Unit = {
        var excessFlows = 0
        var i = 0
        while (i < maxType) {
            excessFlows += expirationQueues(i).size()
            i += 1
        }
        excessFlows -= maxFlows

        if (excessFlows > 0) {
            log.debug(s"Evicting $excessFlows excess flows")
            removeOldestDpFlows(excessFlows)
        }
    }

    private def removeOldestDpFlows(numFlowsToEvict: Int): Unit = {
        var i = 0
        var evicted = 0
        while (i < maxType) {
            val queue = expirationQueues(i)
            var flow: ManagedFlow = null
            while (evicted < numFlowsToEvict &&
                   ({ flow = queue.pollFirst(); flow } ne null)) {
                maybeRemoveFlow(flow)
                evicted += 1
            }
            i += 1
        }
    }

    private def maybeRemoveFlow(flow: ManagedFlow): Unit = {
        flow.unref()
        removeFlow(flow)
    }
}
