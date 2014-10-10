/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
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
