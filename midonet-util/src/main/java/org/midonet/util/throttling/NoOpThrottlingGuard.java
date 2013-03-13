// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

/**
 * A ThrottlingGuard that does nothing.
 */
public class NoOpThrottlingGuard implements ThrottlingGuard {

    @Override
    public void tokenIn() {}

    @Override
    public int numTokens() { return 0; }

    @Override
    public boolean tokenInIfAllowed() { return true; }

    @Override
    public boolean allowed() { return true; }

    @Override
    public void tokenOut() { }
}
