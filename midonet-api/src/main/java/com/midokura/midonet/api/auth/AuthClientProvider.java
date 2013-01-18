/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.config.ConfigProvider;
import com.midokura.midonet.api.auth.keystone.KeystoneClient;
import com.midokura.midonet.api.auth.keystone.KeystoneConfig;

/**
 * Auth client provider.
 */
public class AuthClientProvider implements Provider<AuthClient> {

    private final ConfigProvider provider;

    @Inject
    public AuthClientProvider(ConfigProvider provider) {
        this.provider = provider;
    }

    @Override
    public AuthClient get() {

        AuthConfig config = provider.getConfig(AuthConfig.class);
        if(config.getUseMock()) {
            return new MockAuthClient(config);
        } else {
            KeystoneConfig keystoneConfig = provider.getConfig(
                    KeystoneConfig.class);
            return new KeystoneClient(keystoneConfig);
        }

    }

}
