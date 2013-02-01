// Copyright 2012 Midokura Inc.

package org.midonet.cache;

import org.midonet.util.functors.Callback1;


// TODO: Would this be better with the get calls returning Akka Futures
// instead of invoking Callback functors?
public interface AsyncCache {
    void set(String key, String value);
    void get(String key, Callback1<String> valueCb);
    void getAndTouch(String key, Callback1<String> valueCb);
    int getExpirationSeconds();
}
