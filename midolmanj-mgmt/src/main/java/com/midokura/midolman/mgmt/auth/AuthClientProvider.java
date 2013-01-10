/*
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.mgmt.auth.cloudstack.CloudStackClient;
import com.midokura.midolman.mgmt.auth.cloudstack.CloudStackConfig;
import com.midokura.midolman.mgmt.auth.keystone.KeystoneClient;
import com.midokura.midolman.mgmt.auth.keystone.KeystoneConfig;

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
        // Get the class path of the auth class and load it.
        Class clazz = null;
        try {
            clazz = Class.forName(config.getAuthProvider());
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                    "Auth provider does not exist: "
                            + config.getAuthProvider(), e);
        }

        if (clazz == KeystoneClient.class) {
            KeystoneConfig keystoneConfig = provider.getConfig(
                    KeystoneConfig.class);
            return new KeystoneClient(keystoneConfig);
        } else if (clazz == CloudStackClient.class) {
            CloudStackConfig cloudStackConfig = provider.getConfig(
                    CloudStackConfig.class);
            return new CloudStackClient(cloudStackConfig);
        } else if (clazz == MockAuthClient.class) {
            MockAuthConfig mockAuthConfig = provider.getConfig(
                    MockAuthConfig.class);
            return new MockAuthClient(mockAuthConfig);
        } else {
            try {
                // Try instantiating with no parameter
                return (AuthClient) clazz.newInstance();
            } catch (InstantiationException e) {
                // The class is abstract or interface
                throw new UnsupportedOperationException(
                        "Auth provider is not a valid class: "
                                + config.getAuthProvider(), e);
            } catch (IllegalAccessException e) {
                // The constructor is not public
                throw new UnsupportedOperationException(
                        "Not authorized to instantiate auth provider: "
                                + config.getAuthProvider(), e);
            }
        }
    }
}
