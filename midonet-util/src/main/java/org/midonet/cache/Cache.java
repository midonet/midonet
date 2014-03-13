/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cache;

import java.util.List;
import java.util.Map;

public interface Cache {
    void set(String key, String value);
    void setWithExpiration(String key, String value,
                           int overrideExpirationSeconds);
    String get(String key);
    void delete(String key);
    Map<String, String> dump(int maxEntries);
    String getAndTouch(String key);
    String getAndTouchWithExpiration(String key, int expirationSeconds);
    int getExpirationSeconds();
}
