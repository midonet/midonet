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

import java.util.Enumeration;
import java.util.Map;
import java.util.HashMap;
import javax.servlet.ServletContext;

import org.midonet.config.ConfigProvider;

import static org.midonet.Util.uncheckedCast;

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

    @Override
    public Map<String,Object> getAll() {
        Map<String,Object> values = new HashMap<>();
        Enumeration<String> names = uncheckedCast(ctxt.getInitParameterNames());
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            values.put(name, ctxt.getInitParameter(name));
        }
        return values;
    }

    private String safeGetStringValue(String group, String key, String defaultValue ) {
        String value = ctxt.getInitParameter(group + "-" + key);

        if (value == null) {
            value = defaultValue;
        }

        return value;
    }
}
