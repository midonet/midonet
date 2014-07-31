/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import akka.event.LoggingAdapter

import org.midonet.midolman.state.{FlowState, ConnTrackState, NatState}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.sdn.state.FlowStateTable.Reducer

sealed class StateContext(val pktCtx: PacketContext,
                          val log: LoggingAdapter) extends FlowState
                                                   with ConnTrackState
                                                   with NatState {
    override def clear(): Unit = {
        super[NatState].clear()
        super[ConnTrackState].clear()
    }

    def initialize(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                   natTx: FlowStateTransaction[NatKey, NatBinding]) {
        this.conntrackTx = conntrackTx
        this.natTx = natTx
    }

    private val hasForwardKeys = new Reducer[NatKey, NatBinding, Boolean] {
        override def apply(r: Boolean, k: NatKey, v: NatBinding) = r || {
            k.keyType match {
                case NatKey.FWD_DNAT | NatKey.FWD_SNAT | NatKey.FWD_STICKY_DNAT => true
                case _ => false
            }
        }
    }

    def containsForwardStateKeys: Boolean = {
        if (conntrackTx.size() > 0)
            true
        else
            natTx.fold(false, hasForwardKeys) || natTx.foldOverRefs(false, hasForwardKeys)
    }

}
