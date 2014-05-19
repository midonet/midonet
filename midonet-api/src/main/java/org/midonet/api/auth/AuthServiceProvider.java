/*
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import java.util.Map;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.config.ConfigProvider;

/**
 * Auth service provider.
 */
public class AuthServiceProvider implements Provider<AuthService> {

    private static final Logger log =
            LoggerFactory.getLogger(AuthServiceProvider.class);

    public final static String KEYSTONE_PLUGIN =
            "org.midonet.api.auth.keystone.v2_0.KeystoneService";
    public final static String CLOUDSTACK_PLUGIN =
            "org.midonet.api.auth.cloudstack.CloudStackAuthService";
    public final static String MOCK_PLUGIN =
            "org.midonet.api.auth.MockAuthService";

    private final ConfigProvider provider;
    private final Map<String,Provider<AuthService>> authServices;

    @Inject
    public AuthServiceProvider(
            ConfigProvider provider,
            Map<String, Provider<AuthService>> authServices) {
        this.provider = provider;
        this.authServices = authServices;
    }

    @Override
    public AuthService get() {

        String authService = provider.getConfig(AuthConfig.class).getAuthProvider();

        if(authServices.containsKey(authService)) {
            return authServices.get(authService).get();
        }

        // Get the class path from the configuration and try load it.
        Class<?> clazz = null;
        try {
            clazz = Class.forName(authService);
        } catch (ClassNotFoundException e) {
            log.error("Auth provider doesn't exist {}",
                    authService, e);
            throw new UnsupportedOperationException(
                    "Auth provider does not exist: "
                            + authService, e);
        }
        try {
            log.info("AuthProvider \"{}\" is not recognized, trying to use it " +
                     "anyway (review your web.xml)", authService);
            return (AuthService) clazz.newInstance();
        } catch (InstantiationException e) {
            // The class is abstract or interface
            log.error("Auth provider is not valid: {}",
                    authService, e);
            throw new UnsupportedOperationException(
                    "Auth provider is not a valid class: "
                            + authService, e);
        } catch (IllegalAccessException e) {
            // The constructor is not public
            log.error("Auth provider cannot be instantiated: {}",
                    authService, e);
            throw new UnsupportedOperationException(
                    "Not authorized to instantiate auth provider: "
                            + authService, e);
        }
    }
}
