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

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;

import org.midonet.api.bgp.auth.AdRouteAuthorizer;
import org.midonet.api.bgp.auth.BgpAuthorizer;
import org.midonet.api.filter.auth.ChainAuthorizer;
import org.midonet.api.filter.auth.RuleAuthorizer;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.network.auth.DataClientPortAuthorizer;
import org.midonet.api.network.auth.PortGroupAuthorizer;
import org.midonet.api.network.auth.RouteAuthorizer;
import org.midonet.api.network.auth.RouterAuthorizer;
import org.midonet.brain.services.rest_api.auth.AuthService;
import org.midonet.brain.services.rest_api.auth.AbstractAuthModule;
import org.midonet.brain.services.rest_api.auth.keystone.v2_0.KeystoneService;
import org.midonet.brain.services.rest_api.auth.vsphere.VSphereSSOService;
import org.midonet.brain.services.rest_api.network.auth.PortAuthorizer;
import org.midonet.config.ConfigProvider;

public class DataclientAuthModule extends AbstractAuthModule {

    @Override
    public void bindAuthorizers() {
        bind(PortAuthorizer.class)
            .to(DataClientPortAuthorizer.class)
            .asEagerSingleton();
        bind(AdRouteAuthorizer.class).asEagerSingleton();
        bind(BgpAuthorizer.class).asEagerSingleton();
        bind(BridgeAuthorizer.class).asEagerSingleton();
        bind(ChainAuthorizer.class).asEagerSingleton();
        bind(PortGroupAuthorizer.class).asEagerSingleton();
        bind(RouteAuthorizer.class).asEagerSingleton();
        bind(RouterAuthorizer.class).asEagerSingleton();
        bind(RuleAuthorizer.class).asEagerSingleton();
    }

    @Override
    public void bindAuthServices() {
        bind(AuthService.class).toProvider(
            AuthServiceProvider.class).asEagerSingleton();

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

    // -- Mock --
    @Provides
    @Singleton
    @Inject
    MockAuthConfig provideMockAuthConfig(ConfigProvider provider) {
        return provider.getConfig(MockAuthConfig.class);
    }

}
