/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.inject.Singleton;
import org.midonet.midolman.guice.cluster.DataClientModule;
import org.midonet.midolman.guice.cluster.DataClusterClientModule;

public class NeutronClusterModule extends DataClientModule {

    @Override
    protected void configure() {

        super.configure();
        binder().requireExplicitBindings();

        bind(NetworkZkManager.class).in(Singleton.class);
        expose(NetworkZkManager.class);

        bind(NeutronPlugin.class).to(
                NeutronPluginImpl.class).asEagerSingleton();
        expose(NeutronPlugin.class);
    }
}
