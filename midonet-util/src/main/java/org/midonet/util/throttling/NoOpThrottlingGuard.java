// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A ThrottlingGuard that does nothing.
 */
public class NoOpThrottlingGuard implements ThrottlingGuard {
    private AtomicLong allowedTokens = new AtomicLong(0);

    @Override
    public void tokenIn() {}

    @Override
    public int numTokens() { return 0; }

    @Override
    public boolean tokenInIfAllowed() { return true; }

    @Override
    public boolean allowed() {
        allowedTokens.incrementAndGet();
        return true;
    }

    @Override
    public void tokenOut() { }

    @Override
    public long numAllowedTokens() { return allowedTokens.get(); }

    @Override
    public long numDroppedTokens() { return 0; }
}
