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
package org.midonet.midolman.guice.config;

import com.google.inject.PrivateModule;
import org.apache.commons.configuration.HierarchicalConfiguration;

import org.midonet.config.ConfigProvider;

/**
 * This Guice module will expose a {@link ConfigProvider} instance that everyone
 * can use as the source of their configuration.
 */
public class ConfigProviderModule extends PrivateModule {

    private final ConfigProvider provider;

    public ConfigProviderModule(String path) {
        this.provider = ConfigProvider.fromIniFile(path);
    }

    public ConfigProviderModule(HierarchicalConfiguration configuration) {
        this.provider = ConfigProvider.providerForIniConfig(configuration);
    }

    @Override
    protected void configure() {
        bind(ConfigProvider.class).toInstance(provider);
        expose(ConfigProvider.class);
    }
}
