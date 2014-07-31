/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import akka.event.LoggingAdapter

import org.midonet.midolman.simulation.PacketContext

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

    val log: LoggingAdapter

    /**
     * Eventually reset any state associated with this instance for
     * future reuse.
     */
    def clear(): Unit = { }
}
