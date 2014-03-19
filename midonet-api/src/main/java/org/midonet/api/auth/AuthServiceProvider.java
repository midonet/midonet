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
import org.midonet.api.auth.keystone.KeystoneConfig;
import org.midonet.api.auth.keystone.v2_0.KeystoneClient;
import org.midonet.api.auth.keystone.v2_0.KeystoneService;
import org.midonet.cluster.DataClient;
import org.midonet.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auth service provider.
 */
public class AuthServiceProvider implements Provider<AuthService> {

    private static final Logger log =
        LoggerFactory.getLogger(AuthServiceProvider.class);

    private final ConfigProvider provider;
    private final DataClient dataClient;

    @Inject
    public AuthServiceProvider(ConfigProvider provider, DataClient dataClient) {
        this.provider = provider;
        this.dataClient = dataClient;
    }

    @Override
    public AuthService get() {

        AuthConfig config = provider.getConfig(AuthConfig.class);
        // Get the class path of the auth class and load it.
        Class clazz = null;
        try {
            clazz = Class.forName(config.getAuthProvider());
        } catch (ClassNotFoundException e) {
            log.error("Auth provider doesn't exist {}",
                      config.getAuthProvider(), e);
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
            return new MockAuthService(mockAuthConfig, dataClient);
        } else {
            try {
                log.warn("AuthProvider is not recognized, trying to use a" +
                         "default one but this is likely a config error " +
                         "(review your web.xml)");
                return (AuthService) clazz.newInstance();
            } catch (InstantiationException e) {
                // The class is abstract or interface
                log.error("Auth provider is not valid: {}",
                          config.getAuthProvider(), e);
                throw new UnsupportedOperationException(
                        "Auth provider is not a valid class: "
                                + config.getAuthProvider(), e);
            } catch (IllegalAccessException e) {
                // The constructor is not public
                log.error("Auth provider cannot be instantiated: {}",
                          config.getAuthProvider(), e);
                throw new UnsupportedOperationException(
                        "Not authorized to instantiate auth provider: "
                                + config.getAuthProvider(), e);
            }
        }
    }
}
