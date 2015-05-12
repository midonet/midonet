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

import java.net.MalformedURLException;

import javax.ws.rs.core.SecurityContext;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;

import org.midonet.api.auth.cors.CorsConfig;
import org.midonet.api.auth.keystone.KeystoneConfig;
import org.midonet.api.auth.keystone.v2_0.KeystoneService;
import org.midonet.api.auth.vsphere.VSphereClient;
import org.midonet.api.auth.vsphere.VSphereConfig;
import org.midonet.api.auth.vsphere.VSphereConfigurationException;
import org.midonet.api.auth.vsphere.VSphereSSOService;
import org.midonet.api.rest_api.Authoriser;
import org.midonet.api.rest_api.DataClientAuthoriser;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthException;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.keystone.v2_0.KeystoneClient;
import org.midonet.config.ConfigProvider;

/**
 * Auth bindings.
 */
public class AuthModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(ConfigProvider.class);

        bind(AuthService.class)
            .toProvider(AuthServiceProvider.class)
            .asEagerSingleton();

        MapBinder<String, AuthService> registeredAuthServices =
                MapBinder.newMapBinder(binder(), String.class, AuthService.class);

        registeredAuthServices
                .addBinding(AuthServiceProvider.KEYSTONE_PLUGIN)
                .to(KeystoneService.class);

        registeredAuthServices
                .addBinding(AuthServiceProvider.VSPHERE_PLUGIN)
                .to(VSphereSSOService.class);

        registeredAuthServices
                .addBinding(AuthServiceProvider.MOCK_PLUGIN)
                .to(MockAuthService.class);
    }

    // -- Keystone --
    @Provides @Singleton
    @Inject
    KeystoneConfig provideKeystoneConfig(ConfigProvider provider) {
        return provider.getConfig(KeystoneConfig.class);
    }

    @Provides @Singleton
    @Inject
    KeystoneClient provideKeystoneClient(KeystoneConfig keystoneConfig) {
        return new KeystoneClient(
                keystoneConfig.getServiceHost(),
                keystoneConfig.getServicePort(),
                keystoneConfig.getServiceProtocol(),
                keystoneConfig.getAdminToken());
    }

    // -- vSphere --
    @Provides @Singleton
    @Inject
    VSphereConfig provideVSphereConfig(ConfigProvider provider) {
        return provider.getConfig(VSphereConfig.class);
    }

    @Provides
    @Inject
    VSphereClient provideVSphereClient(VSphereConfig vSphereConfig)
            throws MalformedURLException, AuthException {
        String ignoreServerCertificate =
                vSphereConfig.ignoreServerCert();

        if(ignoreServerCertificate.equalsIgnoreCase("true")) {
            return new VSphereClient(vSphereConfig.getServiceSdkUrl());
        }
        else if(ignoreServerCertificate.equalsIgnoreCase("false")) {
            return new VSphereClient(vSphereConfig.getServiceSdkUrl(),
                    vSphereConfig.getServiceSSLCertFingerprint());
        }

        throw new VSphereConfigurationException("Unrecognized option for " +
                "ignore_server_cert: " + ignoreServerCertificate);
    }

    // -- Mock --
    @Provides @Singleton
    @Inject
    MockAuthConfig provideMockAuthConfig(ConfigProvider provider) {
        return provider.getConfig(MockAuthConfig.class);
    }

    @Provides @Singleton
    @Inject
    CorsConfig provideCorsConfig(ConfigProvider provider) {
        return provider.getConfig(CorsConfig.class);
    }

}
