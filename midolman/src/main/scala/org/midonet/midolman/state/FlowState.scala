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

package org.midonet.midolman.state

import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.simulation.PacketContext
import org.midonet.sdn.flows.FlowTagger.FlowStateTag
import org.midonet.sdn.state.IdleExpiration

object FlowState {
    val DEFAULT_EXPIRATION = 60 seconds

    trait FlowStateKey extends FlowStateTag with IdleExpiration {
        var expiresAfter: Duration = DEFAULT_EXPIRATION
    }
}
/**
 * Base trait for flow state management during a simulation. Implementers of
 * this trait must ensure it is stackable with other state traits.
 */
trait FlowState {
    /**
     * A reference to the PacketContext, which implementers should
     * use for adding flow state tags and installing callbacks.
     */
    val pktCtx: PacketContext

    val log: Logger

    /**
     * Eventually reset any state associated with this instance for
     * future reuse.
     */
    def clear(): Unit = { }
}
