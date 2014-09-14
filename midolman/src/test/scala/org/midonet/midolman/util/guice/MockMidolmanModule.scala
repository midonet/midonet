/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.util.guice

import org.midonet.midolman.guice.MidolmanModule
import org.midonet.midolman.state.{MockNatBlockAllocator, NatBlock, NatRange, NatBlockAllocator}
import org.midonet.util.functors.Callback

class MockMidolmanModule extends MidolmanModule {

    protected override def bindAllocator() {
        bind(classOf[NatBlockAllocator]).toInstance(new MockNatBlockAllocator)
        expose(classOf[NatBlockAllocator])
    }
}
