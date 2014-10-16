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
package org.midonet.api.config;

import javax.servlet.ServletContext;

import com.google.inject.AbstractModule;

import org.midonet.config.ConfigProvider;
import org.midonet.config.providers.ServletContextConfigProvider;

/**
 * Guice module for configuration. Use ServletContext as the configuration
 * source by default, unless the constructor with a filename was called.
 * It is assumed that if using ServletContext, web.xml is context-param
 * elements are defined as {group}-{key}
 */
public class ConfigurationModule extends AbstractModule {

    private final ConfigProvider provider;

    public ConfigurationModule(ServletContext context) {
        this.provider = new ServletContextConfigProvider(context);
    }

    public ConfigurationModule(String filePath) {
        this.provider = ConfigProvider.fromIniFile(filePath);
    }

    @Override
    protected void configure() {
        bind(ConfigProvider.class).toInstance(provider);
    }
}
