/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util;

public class TokenBucketTestRate implements TokenBucketFillRate {

    private int tokens;

    @Override
    public int getNewTokens() {
        return tokens;
    }

    public void setNewTokens(int tokens) {
        this.tokens = tokens;
    }
}
