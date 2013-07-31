// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

import java.util.Collection;

public class CollectionThrottlingCounter<E> implements ThrottlingCounter {
    private final Collection<E> collection;

    public CollectionThrottlingCounter(final Collection<E> collection) {
        this.collection = collection;
    }

    public int tokenIn() { return get(); }

    public int tokenOut() { return get(); }

    public int get() { return collection.size(); }
}
