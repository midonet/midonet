/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import java.net.MalformedURLException;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;

import org.midonet.api.auth.cloudstack.CloudStackAuthService;
import org.midonet.api.auth.cloudstack.CloudStackClient;
import org.midonet.api.auth.cloudstack.CloudStackConfig;
import org.midonet.api.auth.cloudstack.CloudStackJsonParser;
import org.midonet.api.auth.cors.CorsConfig;
import org.midonet.api.auth.keystone.KeystoneConfig;
import org.midonet.api.auth.keystone.v2_0.KeystoneClient;
import org.midonet.api.auth.keystone.v2_0.KeystoneService;
import org.midonet.api.auth.vsphere.VSphereClient;
import org.midonet.api.auth.vsphere.VSphereConfig;
import org.midonet.api.auth.vsphere.VSphereConfigurationException;
import org.midonet.api.auth.vsphere.VSphereSSOService;
import org.midonet.api.bgp.auth.AdRouteAuthorizer;
import org.midonet.api.bgp.auth.BgpAuthorizer;
import org.midonet.api.filter.auth.ChainAuthorizer;
import org.midonet.api.filter.auth.RuleAuthorizer;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.network.auth.PortAuthorizer;
import org.midonet.api.network.auth.PortGroupAuthorizer;
import org.midonet.api.network.auth.RouteAuthorizer;
import org.midonet.api.network.auth.RouterAuthorizer;
import org.midonet.config.ConfigProvider;

/**
 * Auth bindings.
 */
public class AuthModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(ConfigProvider.class);

        bind(AuthService.class).toProvider(
                AuthServiceProvider.class).asEagerSingleton();

        bind(AdRouteAuthorizer.class).asEagerSingleton();
        bind(BgpAuthorizer.class).asEagerSingleton();
        bind(BridgeAuthorizer.class).asEagerSingleton();
        bind(ChainAuthorizer.class).asEagerSingleton();
        bind(PortAuthorizer.class).asEagerSingleton();
        bind(PortGroupAuthorizer.class).asEagerSingleton();
        bind(RouteAuthorizer.class).asEagerSingleton();
        bind(RouterAuthorizer.class).asEagerSingleton();
        bind(RuleAuthorizer.class).asEagerSingleton();

        MapBinder<String, AuthService> registeredAuthServices =
                MapBinder.newMapBinder(binder(), String.class, AuthService.class);

        registeredAuthServices
                .addBinding(AuthServiceProvider.KEYSTONE_PLUGIN)
                .to(KeystoneService.class);

        registeredAuthServices
                .addBinding(AuthServiceProvider.CLOUDSTACK_PLUGIN)
                .to(CloudStackAuthService.class);

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

    // -- CloudStack --
    @Provides @Singleton
    @Inject
    CloudStackConfig provideCloudStackConfig(ConfigProvider provider) {
        return provider.getConfig(CloudStackConfig.class);
    }

    @Provides
    @Inject
    CloudStackClient provideCloudStackClient(CloudStackConfig cloudStackConfig) {
        return new CloudStackClient(
                cloudStackConfig.getApiBaseUri()
                        + cloudStackConfig.getApiPath(),
                cloudStackConfig.getApiKey(),
                cloudStackConfig.getSecretKey(),
                new CloudStackJsonParser());
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
