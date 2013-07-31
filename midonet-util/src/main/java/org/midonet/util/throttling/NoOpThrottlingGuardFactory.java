// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

import java.util.Collection;

public class NoOpThrottlingGuardFactory implements ThrottlingGuardFactory {

    @Override
    public ThrottlingGuard build(String name) {
        return new NoOpThrottlingGuard();
    }

    @Override
    public <E> ThrottlingGuard buildForCollection(
            String name, Collection<E> col) {
        return new NoOpThrottlingGuard();
    }
}
