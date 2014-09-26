/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import akka.event.LoggingAdapter

import org.midonet.midolman.state.{FlowState, ConnTrackState, NatState}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.sdn.state.FlowStateTransaction

object FixPortSets extends Exception {
    override def fillInStackTrace(): Throwable = this
}

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

    def containsForwardStateKeys = conntrackTx.size() > 0 || natTx.size() > 0
}
