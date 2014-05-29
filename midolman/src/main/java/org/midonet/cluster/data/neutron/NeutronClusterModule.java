/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.inject.Singleton;
import org.midonet.midolman.guice.cluster.DataClientModule;

public class NeutronClusterModule extends DataClientModule {

    @Override
    protected void configure() {

        super.configure();
        binder().requireExplicitBindings();

        bind(NetworkZkManager.class).in(Singleton.class);
        expose(NetworkZkManager.class);

        bind(L3ZkManager.class).in(Singleton.class);
        expose(L3ZkManager.class);

        bind(ProviderRouterZkManager.class).in(Singleton.class);
        expose(ProviderRouterZkManager.class);

        bind(ExternalNetZkManager.class).in(Singleton.class);
        expose(ExternalNetZkManager.class);

        bind(SecurityGroupZkManager.class).in(Singleton.class);
        expose(SecurityGroupZkManager.class);

        bind(NetworkApi.class).to(NeutronPlugin.class).asEagerSingleton();
        bind(L3Api.class).to(NeutronPlugin.class).asEagerSingleton();
        bind(SecurityGroupApi.class).to(NeutronPlugin.class).asEagerSingleton();

        expose(NetworkApi.class);
        expose(L3Api.class);
        expose(SecurityGroupApi.class);
    }
}
