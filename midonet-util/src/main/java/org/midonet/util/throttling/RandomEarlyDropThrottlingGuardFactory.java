// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

import org.midonet.util.functors.Callback1;

import java.util.Collection;

public class RandomEarlyDropThrottlingGuardFactory
        implements ThrottlingGuardFactory {
    private final int highWaterMark;
    private final int lowWaterMark;

    public RandomEarlyDropThrottlingGuardFactory(int lowWaterMark,
                                                 int highWaterMark) {
        this.highWaterMark = highWaterMark;
        this.lowWaterMark = lowWaterMark;
    }

    @Override
    public ThrottlingGuard build(String name) {
        return new RandomEarlyDropThrottlingGuard(
                name, new AtomicThrottlingCounter(),
                this.highWaterMark, this.lowWaterMark);

    }

    @Override
    public <E> ThrottlingGuard buildForCollection(
            String name, Collection<E> col) {
        return new RandomEarlyDropThrottlingGuard(
                name, new CollectionThrottlingCounter<E>(col),
                this.highWaterMark, this.lowWaterMark);
    }
}
