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

import java.util.ArrayList


import com.google.protobuf.MessageLite

import org.midonet.midolman.state.{NatLeaser, ConnTrackState, NatState}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.state.TraceState
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.odp.flows.FlowAction
import org.midonet.packets.FlowStateEthernet
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.Clearable

trait StateContext extends Clearable
                   with ConnTrackState
                   with NatState
                   with TraceState { this: PacketContext =>

    val stateMessage = new Array[Byte](
        FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
    var stateMessageLength = 0
    val stateActions = new ArrayList[FlowAction]()

    def initialize(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                   natTx: FlowStateTransaction[NatKey, NatBinding],
                   natLeaser: NatLeaser,
                   traceTx: FlowStateTransaction[TraceKey, TraceContext]) {
        this.conntrackTx = conntrackTx
        this.natTx = natTx
        this.natLeaser = natLeaser
        this.traceTxReadOnly = traceTx
    }

    def containsFlowState =
        conntrackTx.size() > 0 || natTx.size() > 0 || tracingEnabled

    def commitStateTransactions(): Unit ={
        conntrackTx.commit()
        natTx.commit()
    }

    abstract override def clear(): Unit = {
        super.clear()
        stateMessageLength = 0;
        stateActions.clear()
    }
}
