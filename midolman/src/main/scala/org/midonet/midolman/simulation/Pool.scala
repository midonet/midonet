/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID
import akka.event.LoggingBus

/**
 * Pool.
 *
 * Placeholder class.
 */
class Pool(val id: UUID, val poolMembers: Set[PoolMember],
           val loggingBus: LoggingBus) {
}
