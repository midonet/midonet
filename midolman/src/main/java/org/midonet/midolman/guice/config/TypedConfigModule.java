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

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.config.ConfigProvider;

/**
 * Simple module that allows one to expose only a specific VlanBridgeconfig interface
 * backed up by the system config provider.
 */
public class TypedConfigModule<T> extends AbstractModule {
    private Class<T> configType;

    @Inject
    ConfigProvider configProvider;

    public TypedConfigModule(Class<T> configType) {
        this.configType = configType;
    }

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);
        bind(configType)
            .toProvider(new TypedConfigProvider<T>(configType));
    }

    static class TypedConfigProvider<T> implements Provider<T> {

        @javax.inject.Inject
        ConfigProvider configProvider;
        private Class<T> configType;

        public TypedConfigProvider(Class<T> configType) {
            this.configType = configType;
        }

        @Override
        public T get() {
            return configProvider.getConfig(configType);
        }
    }
}
