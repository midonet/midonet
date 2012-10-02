/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.cache;

import com.midokura.util.functors.Callback1;


public interface Cache {
    void set(String key, String value);
    String get(String key);
    void getAsync(String key, Callback1<String> valueCb);
    String getAndTouch(String key);
    int getExpirationSeconds();
}
