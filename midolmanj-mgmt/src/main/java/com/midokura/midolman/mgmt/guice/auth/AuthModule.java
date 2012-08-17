/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.auth;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.mgmt.auth.AuthClient;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.SimpleAuthorizer;
import com.midokura.midolman.mgmt.auth.keystone.KeystoneConfig;

/**
 * Auth bindings.
 */
public class AuthModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(ConfigProvider.class);

        bind(AuthClient.class).toProvider(
                AuthClientProvider.class).asEagerSingleton();

        bind(Authorizer.class).to(SimpleAuthorizer.class).asEagerSingleton();
    }

    @Provides
    @Inject
    KeystoneConfig provideKeystoneConfig(ConfigProvider provider) {
        return provider.getConfig(KeystoneConfig.class);
    }
}
