/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
