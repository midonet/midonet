/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import org.midonet.config.ConfigProvider;
import org.midonet.api.network.auth.*;
import org.midonet.api.auth.cors.CorsConfig;
import org.midonet.api.auth.keystone.KeystoneConfig;
import org.midonet.api.bgp.auth.AdRouteAuthorizer;
import org.midonet.api.bgp.auth.BgpAuthorizer;
import org.midonet.api.filter.auth.ChainAuthorizer;
import org.midonet.api.filter.auth.RuleAuthorizer;
import org.midonet.api.network.auth.*;
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
