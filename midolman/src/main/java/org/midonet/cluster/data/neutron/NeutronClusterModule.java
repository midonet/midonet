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

        bind(NetworkApi.class).to(NeutronPlugin.class).asEagerSingleton();
        bind(L3Api.class).to(NeutronPlugin.class).asEagerSingleton();
        bind(SecurityGroupApi.class).to(NeutronPlugin.class).asEagerSingleton();

        expose(NetworkApi.class);
        expose(L3Api.class);
        expose(SecurityGroupApi.class);
    }
}
