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

import java.util.{ArrayList, HashSet, UUID}

import com.google.protobuf.MessageLite
import org.midonet.midolman.state.{NatLeaser, FlowState, ConnTrackState, NatState}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.odp.flows.FlowAction
import org.midonet.sdn.state.FlowStateTransaction

trait StateContext extends FlowState
                   with ConnTrackState
                   with NatState { this: PacketContext =>

    var stateMessage: MessageLite = _
    val stateActions = new ArrayList[FlowAction]()

    def initialize(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                   natTx: FlowStateTransaction[NatKey, NatBinding],
                   natLeaser: NatLeaser) {
        this.conntrackTx = conntrackTx
        this.natTx = natTx
        this.natLeaser = natLeaser
    }

    def hasFlowState = conntrackTx.size() > 0 || natTx.size() > 0
}
