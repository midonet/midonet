/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
