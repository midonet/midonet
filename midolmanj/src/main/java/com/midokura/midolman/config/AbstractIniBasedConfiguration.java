/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.config;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;

/**
 * Class that wraps a {@link HierarchicalConfiguration} instance and provides
 * better methods for getting the configuration entries.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/30/12
 */
public abstract class AbstractIniBasedConfiguration {

    protected HierarchicalConfiguration config;

    protected AbstractIniBasedConfiguration(String configFileLocation)
        throws ConfigurationException {
        this(new HierarchicalINIConfiguration(configFileLocation));
    }

    protected AbstractIniBasedConfiguration(HierarchicalConfiguration config) {
        this.config = config;
    }

    protected int safeGetInt(String group, String key, int defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getInt(key, defaultValue);
        } catch (IllegalArgumentException ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }

    protected String safeGetString(String group, String key,
                                   String defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getString(key, defaultValue);
        } catch (IllegalArgumentException ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }

    protected long safeGetLong(String group, String key, long defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getLong(key, defaultValue);
        } catch (IllegalArgumentException ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }

}
