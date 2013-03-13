// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

import java.util.concurrent.atomic.AtomicInteger;

public class AtomicThrottlingCounter implements ThrottlingCounter {
    private final AtomicInteger counter = new AtomicInteger(0);

    public int tokenIn() { return counter.incrementAndGet(); }

    public int tokenOut() { return counter.decrementAndGet(); }

    public int get() { return counter.get(); }
}
