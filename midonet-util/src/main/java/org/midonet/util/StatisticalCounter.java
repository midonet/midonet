/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

import org.midonet.Util;
import sun.misc.Unsafe;

/**
 * This class implements an efficient multi-threaded counter. This is achieved
 * by segregating writes into independent per-thread counters that are
 * aggregated on reads. As such, it is not a precise counter.
 */
public class StatisticalCounter {
    /* This is used to pad the counters so they fall on different cache lines,
     * to prevent those lines from ping-ponging across CPUs. We assume 64 byte
     * cache lines.
     */
    private static final int PADDING = 8;

    /* Unsafe enables atomic operations on the individual contents of an array.
     * We need the base offset of the long[] class, as well as the amount of
     * memory each entry takes.
     */
    private static final Unsafe UNSAFE = Util.getUnsafe();
    private static final long BASE  = UNSAFE.arrayBaseOffset(long[].class);
    private static final long SCALE = UNSAFE.arrayIndexScale(long[].class);

    private final long[] counters;

    public StatisticalCounter(int counters) {
        this.counters = new long[counters * PADDING];
    }

    public long addAndGet(int index, int count) {
        return counters[index * PADDING] += count;
    }

    public long addAndGetAtomic(int index, int count) {
        long bufferAddress = BASE + (index * PADDING * SCALE);
        long c, nc;
        do {
            // We need a volatile read to avoid the compiler hoisting
            // this outside the loop.
            c = UNSAFE.getLongVolatile(counters, bufferAddress);
            nc = c + count;
        } while (!UNSAFE.compareAndSwapLong(counters, bufferAddress, c, nc));
        return nc;
    }

    public long getValue() {
        long sum = 0;
        for (int i = 0; i < counters.length; i += PADDING) {
            sum += counters[i];
        }
        return sum;
    }
}
