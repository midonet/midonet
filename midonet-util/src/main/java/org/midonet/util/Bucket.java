/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is an utility class that wraps a token bucket and applies a multiplier
 * to the number of returned tokens. To avoid starving other buckets, it exposes
 * a method to return any multiplied tokens that were not consumed back to the
 * system. Example usage:
 *
 * bucket.prepare();
 * while (bucket.consumeToken() && readPacket() > 0) { ... }
 * bucket.done();
 */
public class Bucket {
    protected static final Logger log = LoggerFactory.getLogger(Bucket.class);
    protected static final boolean isTraceEnabled = log.isTraceEnabled();

    private final TokenBucket tb;
    private final int multiplier;
    private final StatisticalCounter counter;
    private final int index;
    private final boolean atomic;
    private int tokens;

    public Bucket(TokenBucket tb, int multiplier,
                  StatisticalCounter counter, int index,
                  boolean atomic) {
        this.tb = tb;
        this.multiplier = multiplier;
        this.counter = counter;
        this.index = index;
        this.atomic = atomic;
    }

    /* Prepares the Bucket for token consumption. This method exists solely
     * for symmetry.
     */
    public void prepare() {
        tokens = tb.tryGet(1) * multiplier;
    }

    /* Tries to consume a token from the bucket, returning true or
     * false accordingly.
     */
    public boolean consumeToken() {
        if (tokens > 0) {
            tokens -= 1;
            if (isTraceEnabled) {
                log.trace("[{}|{}] got 1 new token", new Object[] {
                        Thread.currentThread().getId(), tb.getName()});
            }
            return true;
        }

        if (tb.tryGet(1) == 1) {
            tokens = multiplier - 1;
            return true;
        }

        return false;
    }

    /* Signals that the caller is done consuming tokens. It returns any
     * unconsumed tokens back to the system.
     */
    public void done() {
        if (atomic)
            counter.addAndGetAtomic(index, tokens);
        else
            counter.addAndGet(index, tokens);
        tokens = 0;
    }

    public TokenBucket underlyingTokenBucket() {
        return tb;
    }

    /* Creates a Bucket that always allows tokens to be consumed.
     */
    public static Bucket BOTTOMLESS = new Bucket(null, 0, null, 0, false) {
        @Override
        public final void prepare() { }

        @Override
        public final boolean consumeToken() { return true; }

        @Override
        public final void done() { }
    };
}
