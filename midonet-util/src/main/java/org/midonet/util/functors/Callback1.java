// Copyright 2012 Midokura Inc.
//
// Callback1.java --  One-argument functor interface for callbacks.

package org.midonet.util.functors;

public interface Callback1<T> {
    void call(T v);
}
