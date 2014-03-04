/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util;

public class TokenBucketSystemRate implements TokenBucketFillRate {
    private final StatisticalCounter packetsOut;
    private PaddedAtomicLong lastCount;

    public TokenBucketSystemRate(StatisticalCounter packetsOut) {
        this.packetsOut = packetsOut;
        lastCount = new PaddedAtomicLong(0);
    }

    @Override
    public int getNewTokens() {
        long c = lastCount.get();
        long nc = packetsOut.getValue();
        return nc <= c && !lastCount.compareAndSet(c, nc) ? 0 : (int) (nc - c);
    }
}
