/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
