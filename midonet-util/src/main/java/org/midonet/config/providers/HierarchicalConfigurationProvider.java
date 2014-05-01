/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.config.providers;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;

import org.midonet.config.ConfigProvider;

public class HierarchicalConfigurationProvider extends ConfigProvider {

    private final HierarchicalConfiguration config;

    public HierarchicalConfigurationProvider(HierarchicalConfiguration config) {
        this.config = config;
    }

    @Override
    public String getValue(String group, String key, String defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getString(key, defaultValue);
        } catch (Throwable ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }

    @Override
    public int getValue(String group, String key, int defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getInt(key, defaultValue);
        } catch (Throwable ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }

    @Override
    public long getValue(String group, String key, long defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getLong(key, defaultValue);
        } catch (Throwable ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }

    @Override
    public boolean getValue(String group, String key, boolean defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
           return subConfig.getBoolean(key, defaultValue);
        } catch (Throwable ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }
}
