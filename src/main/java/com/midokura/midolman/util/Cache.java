package com.midokura.midolman.util;

public interface Cache {

    void set(String key, String value);
    String get(String key);
}
