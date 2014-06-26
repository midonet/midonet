/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * This class wraps an AtomicInteger, padding the cache line it is on. This
 * ensures that there will be no false sharing when multiple threads are
 * accessing different, but adjacent fields. We assume 64 byte cache lines,
 * so we pad the 8 byte long with 48 additional bytes plus the 8/16 byte header.
 */
public class PaddedAtomicLong extends AtomicLong {

    private static final long serialVersionUID = 1L;

    public PaddedAtomicLong() {
    }

    public PaddedAtomicLong(final long initialValue) {
        super(initialValue);
    }

    public volatile long p1, p2, p3, p4, p5, p6 = 7;
}
