/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.config;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.config.ConfigProvider;

/**
 * Simple module that allows one to expose only a specific Config interface
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
