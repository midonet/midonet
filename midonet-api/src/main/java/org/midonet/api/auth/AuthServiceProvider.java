/*
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.midonet.api.auth.cloudstack.CloudStackAuthService;
import org.midonet.api.auth.cloudstack.CloudStackClient;
import org.midonet.api.auth.cloudstack.CloudStackConfig;
import org.midonet.api.auth.cloudstack.CloudStackJsonParser;
import org.midonet.api.auth.keystone.v2_0.KeystoneClient;
import org.midonet.api.auth.keystone.KeystoneConfig;
import org.midonet.api.auth.keystone.v2_0.KeystoneService;
import org.midonet.config.ConfigProvider;

/**
 * Auth service provider.
 */
public class AuthServiceProvider implements Provider<AuthService> {

    private final ConfigProvider provider;

    @Inject
    public AuthServiceProvider(ConfigProvider provider) {
        this.provider = provider;
    }

    @Override
    public AuthService get() {

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

        if (clazz == KeystoneService.class) {

            KeystoneConfig keystoneConfig = provider.getConfig(
                    KeystoneConfig.class);

            KeystoneClient keystoneClient = new KeystoneClient(
                    keystoneConfig.getServiceHost(),
                    keystoneConfig.getServicePort(),
                    keystoneConfig.getServiceProtocol(),
                    keystoneConfig.getAdminToken());

            return new KeystoneService(keystoneClient, keystoneConfig);

        } else if (clazz == CloudStackAuthService.class) {
            CloudStackConfig cloudStackConfig = provider.getConfig(
                    CloudStackConfig.class);

            CloudStackClient cloudStackClient = new CloudStackClient(
                    cloudStackConfig.getApiBaseUri()
                            + cloudStackConfig.getApiPath(),
                    cloudStackConfig.getApiKey(),
                    cloudStackConfig.getSecretKey(),
                    new CloudStackJsonParser()
            );
            return new CloudStackAuthService(cloudStackClient);
        } else if (clazz == MockAuthService.class) {
            MockAuthConfig mockAuthConfig = provider.getConfig(
                    MockAuthConfig.class);
            return new MockAuthService(mockAuthConfig);
        } else {
            try {
                // Try instantiating with no parameter
                return (AuthService) clazz.newInstance();
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
