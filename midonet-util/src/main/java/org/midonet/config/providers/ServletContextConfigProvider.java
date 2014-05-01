/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.config.providers;

import javax.servlet.ServletContext;

import org.midonet.config.ConfigProvider;

/**
 * ConfigProvider which adapts the servlet context parameters to be automatically
 * used by config interfaces.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
 */
public class ServletContextConfigProvider extends ConfigProvider {
    private ServletContext ctxt;

    public ServletContextConfigProvider(ServletContext ctxt) {
        this.ctxt = ctxt;
    }

    @Override
    public String getValue(String group, String key, String defaultValue) {
        String value = safeGetStringValue(group, key, defaultValue);
        if (value.trim().length() == 0) {
            return defaultValue;
        }

        return value;
    }

    @Override
    public int getValue(String group, String key, int defaultValue) {
        String value = safeGetStringValue(group, key, "" + defaultValue);

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @Override
    public long getValue(String group, String key, long defaultValue) {
        String value = safeGetStringValue(group, key, "" + defaultValue);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @Override
    public boolean getValue(String group, String key, boolean defaultValue) {
        String value = safeGetStringValue(group, key, "" + defaultValue);
        if (value.trim().length() == 0) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value);
    }

    private String safeGetStringValue(String group, String key, String defaultValue ) {
        String value = ctxt.getInitParameter(group + "-" + key);

        if (value == null) {
            value = defaultValue;
        }

        return value;
    }
}
