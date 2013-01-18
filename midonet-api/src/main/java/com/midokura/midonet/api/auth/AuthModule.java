/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.midokura.config.ConfigProvider;
import com.midokura.midonet.api.network.auth.*;
import com.midokura.midonet.api.vpn.auth.VpnAuthorizer;
import com.midokura.midonet.api.auth.cors.CorsConfig;
import com.midokura.midonet.api.auth.keystone.KeystoneConfig;
import com.midokura.midonet.api.bgp.auth.AdRouteAuthorizer;
import com.midokura.midonet.api.bgp.auth.BgpAuthorizer;
import com.midokura.midonet.api.filter.auth.ChainAuthorizer;
import com.midokura.midonet.api.filter.auth.RuleAuthorizer;

/**
 * Auth bindings.
 */
public class AuthModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(ConfigProvider.class);

        bind(AuthClient.class).toProvider(
                AuthClientProvider.class).asEagerSingleton();

        bind(AdRouteAuthorizer.class).asEagerSingleton();
        bind(BgpAuthorizer.class).asEagerSingleton();
        bind(BridgeAuthorizer.class).asEagerSingleton();
        bind(ChainAuthorizer.class).asEagerSingleton();
        bind(PortAuthorizer.class).asEagerSingleton();
        bind(PortGroupAuthorizer.class).asEagerSingleton();
        bind(RouteAuthorizer.class).asEagerSingleton();
        bind(RouterAuthorizer.class).asEagerSingleton();
        bind(RuleAuthorizer.class).asEagerSingleton();
        bind(VpnAuthorizer.class).asEagerSingleton();

    }

    @Provides
    @Inject
    KeystoneConfig provideKeystoneConfig(ConfigProvider provider) {
        return provider.getConfig(KeystoneConfig.class);
    }

    @Provides
    @Inject
    CorsConfig provideCorsConfig(ConfigProvider provider) {
        return provider.getConfig(CorsConfig.class);
    }

}
