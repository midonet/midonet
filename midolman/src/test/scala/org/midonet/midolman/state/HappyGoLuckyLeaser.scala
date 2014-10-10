/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import com.typesafe.scalalogging.Logger
import com.yammer.metrics.core.Clock
import org.slf4j.helpers.NOPLogger

import org.midonet.util.MockClock

object HappyGoLuckyLeaser extends NatLeaser {

    override val log = Logger(NOPLogger.NOP_LOGGER)
    override val allocator: NatBlockAllocator = new MockNatBlockAllocator
    override val clock: Clock = new MockClock
}
