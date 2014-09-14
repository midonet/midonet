/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import akka.event.{NoLogging, LoggingAdapter}
import com.yammer.metrics.core.Clock
import org.midonet.util.MockClock

object HappyGoLuckyLeaser extends NatLeaser {

    override val log: LoggingAdapter = NoLogging
    override val allocator: NatBlockAllocator = new MockNatBlockAllocator
    override val clock: Clock = new MockClock
}
