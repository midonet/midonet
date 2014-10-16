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

package org.midonet.midolman.simulation

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.state.{NatLeaser, FlowState, ConnTrackState, NatState}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.sdn.state.FlowStateTransaction

object FixPortSets extends Exception {
    override def fillInStackTrace(): Throwable = this
}

sealed class StateContext(override val pktCtx: PacketContext,
                          override val log: Logger) extends FlowState
                                                   with ConnTrackState
                                                   with NatState {
    override def clear(): Unit = {
        super[NatState].clear()
        super[ConnTrackState].clear()
    }

    def initialize(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                   natTx: FlowStateTransaction[NatKey, NatBinding],
                   natLeaser: NatLeaser) {
        this.conntrackTx = conntrackTx
        this.natTx = natTx
        this.natLeaser = natLeaser
    }

    def containsForwardStateKeys = conntrackTx.size() > 0 || natTx.size() > 0
}
