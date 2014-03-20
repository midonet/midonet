/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class wraps an AtomicInteger, padding the cache line it is on. This
 * ensures that there will be no false sharing when multiple threads are
 * accessing different, but adjacent fields. We assume 64 byte cache lines,
 * so we pad the 4 byte integer with 48 additional bytes plus the 8 byte-aliged
 * header of 8/16 bytes.
 */
public class PaddedAtomicInteger extends AtomicInteger {
    public PaddedAtomicInteger() {
    }

    public PaddedAtomicInteger(final int initialValue) {
        super(initialValue);
    }

    public volatile long p1, p2, p3, p4, p5, p6 = 7;
}
