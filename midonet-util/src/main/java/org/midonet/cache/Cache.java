/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cache;


public interface Cache {
    void set(String key, String value);
    String get(String key);
    String getAndTouch(String key);
    int getExpirationSeconds();
}
