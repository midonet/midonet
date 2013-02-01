/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.util.functors;

public interface Callback3<A, B, C> {

    void call(A a, B b, C c);
}
