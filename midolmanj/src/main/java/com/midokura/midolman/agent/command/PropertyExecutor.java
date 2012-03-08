/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public enum PropertyExecutor {
    mtu("mtu", Integer.class, MtuCommandExecutor.class);

    private String key;
    private Class type;
    private Class<? extends CommandExecutor> executor;
    private static final Map<String, PropertyExecutor> lookup = new HashMap<String, PropertyExecutor>();

    static {
        for (PropertyExecutor p : EnumSet.allOf(PropertyExecutor.class)) {
            lookup.put(p.getKey(), p);
        }
    }

    private PropertyExecutor(String key, Class type,
                             Class<? extends CommandExecutor> executor) {
        this.key = key;
        this.type = type;
        this.executor = executor;
    }

    public String getKey() {
        return key;
    }

    public Class getType() {
        return type;
    }

    public Class<? extends CommandExecutor> getExecutor() {
        return executor;
    }

    public static PropertyExecutor get(String key) {
        return lookup.get(key);
    }
}


