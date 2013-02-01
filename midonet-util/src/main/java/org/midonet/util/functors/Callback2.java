/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.util.functors;

public interface Callback2<A, B> {
    void call(A a, B b);
}
