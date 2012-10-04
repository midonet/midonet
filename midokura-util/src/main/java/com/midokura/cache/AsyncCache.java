// Copyright 2012 Midokura Inc.

package com.midokura.cache;

import com.midokura.util.functors.Callback1;


public interface AsyncCache {
    void set(String key, String value);
    void get(String key, Callback1<String> valueCb);
    void getAndTouch(String key, Callback1<String> valueCb);
    int getExpirationSeconds();
}
