/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cache;

import java.util.List;
import java.util.Map;

public interface Cache {
    void set(String key, String value);
    String get(String key);
    void delete(String key);
    Map<String, String> dump(int maxEntries);
    String getAndTouch(String key);
    int getExpirationSeconds();
}
