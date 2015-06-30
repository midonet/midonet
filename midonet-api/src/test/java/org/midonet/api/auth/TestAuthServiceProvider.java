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

import javax.servlet.ServletContext;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.util.Modules;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.midonet.api.auth.keystone.KeystoneConfig;
import org.midonet.api.auth.vsphere.VSphereConfig;
import org.midonet.api.auth.vsphere.VSphereSSOService;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.keystone.v2_0.KeystoneService;
import org.midonet.config.ConfigProvider;
import org.midonet.config.providers.ServletContextConfigProvider;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The main intent of this test class is to make sure that all
 * the guice dependencies are wired correctly and to ensure
 * that all the available plugins have been installed.
 *
 * Supported plugins:
 *
 * - Keystone
 * - vSphere
 * - Mock
 */
public class TestAuthServiceProvider {

    private Injector standardInjector;
    private Injector customConfigProviderInjector;

    @Mock
    private DataClient mockDataClient;
    @Mock
    private ConfigProvider mockConfigProvider;
    @Mock
    private ServletContext mockServletContext;
    @Mock
    private AuthConfig mockAuthConfig;
    @Mock
    private VSphereConfig mockVSphereConfig;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final ConfigProvider cfgProvider =
            new ServletContextConfigProvider(mockServletContext);
        standardInjector = Guice.createInjector(
                new AuthModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ConfigProvider.class).toInstance(cfgProvider);
                        bind(DataClient.class).toInstance(mockDataClient);
                    }
                }
        );

        // Specify an auth service plugin to allow the following injector
        // instantiation
        when(mockConfigProvider.getConfig(AuthConfig.class))
                .thenReturn(mockAuthConfig);
        when(mockAuthConfig.getAuthProvider())
                .thenReturn(AuthServiceProvider.MOCK_PLUGIN);

        // A custom injector where the auth configuration can be controlled
        // from outside through the mockConfigProvider/mockAuthConfig
        customConfigProviderInjector = Guice.createInjector(
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(DataClient.class).toInstance(mockDataClient);
                        bind(ConfigProvider.class)
                                .toInstance(mockConfigProvider);

                    }
                },
                Modules.override(new AuthModule()).with(
                    new AbstractModule() {
                        @Override
                        protected void configure() {
                        // To be tested against all the plugins is convenient
                        // for the AuthServiceProvider to not be a Singleton
                        bind(AuthService.class)
                                .toProvider(AuthServiceProvider.class);
                        }
                    }
                )
        );
    }

    @Test
    public void testDefaultAuthService() {
        AuthService defaultAuthService =
                standardInjector.getInstance(AuthService.class);

        assertTrue(defaultAuthService instanceof MockAuthService);
    }

    @Test
    public void testInstalledKeystonePlugin() {
        when(mockAuthConfig.getAuthProvider())
                .thenReturn(AuthServiceProvider.KEYSTONE_PLUGIN);
        when(mockConfigProvider.getConfig(KeystoneConfig.class))
                .thenReturn(mock(KeystoneConfig.class));

        Provider<AuthService> authServiceProvider =
                customConfigProviderInjector.getProvider(AuthService.class);

        assertTrue(authServiceProvider.get() instanceof KeystoneService);
    }

    @Test
    public void testInstalledMockPlugin() {
        when(mockAuthConfig.getAuthProvider())
                .thenReturn(AuthServiceProvider.MOCK_PLUGIN);
        when(mockConfigProvider.getConfig(MockAuthConfig.class))
                .thenReturn(mock(MockAuthConfig.class));

        Provider<AuthService> authServiceProvider =
                customConfigProviderInjector.getProvider(AuthService.class);

        assertTrue(authServiceProvider.get() instanceof MockAuthService);
    }

    @Test
    public void testInstalledVSpherePlugin() {
        when(mockAuthConfig.getAuthProvider())
                .thenReturn(AuthServiceProvider.VSPHERE_PLUGIN);
        when(mockConfigProvider.getConfig(VSphereConfig.class))
                .thenReturn(mockVSphereConfig);
        when(mockVSphereConfig.getServiceSdkUrl())
                .thenReturn("https://localhost/sdk");
        when(mockVSphereConfig.getServiceSSLCertFingerprint())
                .thenReturn("");
        when(mockVSphereConfig.ignoreServerCert())
                .thenReturn("true");

        Provider<AuthService> authServiceProvider =
                customConfigProviderInjector.getProvider(AuthService.class);

        assertTrue(authServiceProvider.get() instanceof VSphereSSOService);
    }

    @Test(expected=com.google.inject.ProvisionException.class)
    public void testUnsupportedPlugin() {
        when(mockAuthConfig.getAuthProvider())
                .thenReturn("org.midonet.api.auth.UNSUPPORTED");

        customConfigProviderInjector.getInstance(AuthService.class);
    }

    @Test
    public void testFallback() {
        when(mockAuthConfig.getAuthProvider())
                .thenReturn("org.midonet.api.auth.FakeTestAuthService");

        Provider<AuthService> authServiceProvider =
                customConfigProviderInjector.getProvider(AuthService.class);

        assertTrue(authServiceProvider.get() instanceof FakeTestAuthService);
    }
}
