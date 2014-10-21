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
package org.midonet.api.auth;

import java.util.Map;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.jcabi.aspects.LogExceptions;
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
    public final static String VSPHERE_PLUGIN =
            "org.midonet.api.auth.vsphere.VSphereSSOService";

    private final ConfigProvider provider;
    private final Map<String,Provider<AuthService>> authServices;

    @Inject
    public AuthServiceProvider(
            ConfigProvider provider,
            Map<String, Provider<AuthService>> authServices) {
        this.provider = provider;
        this.authServices = authServices;
    }

    @LogExceptions
    @Override
    public AuthService get() {

        String authService =
                provider.getConfig(AuthConfig.class).getAuthProvider();

        if(authServices.containsKey(authService)) {
            log.info("Using the {} authentication plugin", authService);
            return authServices.get(authService).get();
        }

        // Get the class path from the configuration and try load it.
        Class<?> clazz;
        try {
            clazz = Class.forName(authService);
        }
        catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                    "Auth provider does not exist: " + authService, e);
        }
        try {
            log.info("AuthProvider \"{}\" is not recognized, trying to use " +
                    "it anyway (review your web.xml)", authService);

            return (AuthService) clazz.newInstance();
        }
        catch (InstantiationException e) {
            // The class is abstract or interface
            throw new UnsupportedOperationException(
                    "Auth provider is not a valid class: " + authService, e);
        }
        catch (IllegalAccessException e) {
            // The constructor is not public
            throw new UnsupportedOperationException(
                    "Auth provider cannot be instantiated: "
                            + authService, e);
        }
    }
}
