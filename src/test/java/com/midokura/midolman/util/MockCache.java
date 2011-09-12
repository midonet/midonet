package com.midokura.midolman.util;

import java.util.HashMap;
import java.util.Map;

public class MockCache implements Cache {

    private Map<String, String> map = new HashMap<String, String>();

    @Override
    public void set(String key, String value) {
        map.put(key, value);
    }

    @Override
    public String get(String key) {
        return map.get(key);
    }

}
