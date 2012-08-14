// Copyright 2012 Midokura Inc.
//
// Callback1.java --  One-argument functor interface for callbacks.

package com.midokura.util.functors;

public interface Callback1<T> {
    void call(T v);
}
