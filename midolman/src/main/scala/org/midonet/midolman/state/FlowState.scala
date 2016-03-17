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
import org.midonet.odp.FlowMatch
import org.midonet.packets.FlowStatePackets
import org.midonet.packets.FlowStateStore.DEFAULT_EXPIRATION
import org.midonet.sdn.flows.FlowTagger.FlowStateTag
import org.midonet.sdn.state.IdleExpiration
import org.midonet.util.Clearable

object FlowState {

    trait FlowStateKey extends FlowStateTag with IdleExpiration {
        var expiresAfter: Duration = DEFAULT_EXPIRATION
    }

    @inline
    def isStateMessage(fmatch: FlowMatch): Boolean = {
        fmatch.getTunnelKey == FlowStatePackets.TUNNEL_KEY
    }
}
/**
 * Base trait for flow state management during a simulation. Implementers of
 * this trait must ensure it is stackable with other state traits.
 */
trait FlowState extends Clearable { this: PacketContext => }
