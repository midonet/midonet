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
package org.midonet.cluster.data.neutron;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;

public class NeutronClusterModule extends PrivateModule {

    @Override
    protected void configure() {

        binder().requireExplicitBindings();

        // Bind ZK Managers
        bind(NetworkZkManager.class).asEagerSingleton();
        bind(L3ZkManager.class).asEagerSingleton();
        bind(ProviderRouterZkManager.class).asEagerSingleton();
        bind(ExternalNetZkManager.class).asEagerSingleton();
        bind(SecurityGroupZkManager.class).asEagerSingleton();
        bind(LBZkManager.class).in(Singleton.class);

        expose(NetworkZkManager.class);
        expose(L3ZkManager.class);
        expose(ProviderRouterZkManager.class);
        expose(ExternalNetZkManager.class);
        expose(SecurityGroupZkManager.class);
        expose(LBZkManager.class);

        // Bind Neutron Plugin API
        bind(NetworkApi.class).to(NeutronPlugin.class).asEagerSingleton();
        bind(L3Api.class).to(NeutronPlugin.class).asEagerSingleton();
        bind(SecurityGroupApi.class).to(NeutronPlugin.class).asEagerSingleton();
        bind(LoadBalancerApi.class).to(NeutronPlugin.class).asEagerSingleton();

        expose(NetworkApi.class);
        expose(L3Api.class);
        expose(SecurityGroupApi.class);
        expose(LoadBalancerApi.class);
    }
}
