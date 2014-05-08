/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

public class TokenBucketSystemRate implements TokenBucketFillRate {
    private final StatisticalCounter packetsOut;
    private final int multiplier;
    private long lastCount;

    public TokenBucketSystemRate(StatisticalCounter packetsOut) {
        this(packetsOut, 1);
    }

    public TokenBucketSystemRate(StatisticalCounter packetsOut, int multiplier) {
        this.packetsOut = packetsOut;
        this.multiplier = multiplier;
    }

    @Override
    public int getNewTokens() {
        long c = lastCount;
        long nc = packetsOut.getValue() / multiplier;

        if (nc > c) {
            lastCount = nc;
            return (int)(nc - c);
        }
        return 0;
    }
}
