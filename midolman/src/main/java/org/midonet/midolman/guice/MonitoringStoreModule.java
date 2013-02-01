/*
* Copyright 2012 Midokura PTE LTD.
*/
package org.midonet.midolman.guice;

import com.google.inject.PrivateModule;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.monitoring.store.CassandraStoreProvider;
import org.midonet.midolman.monitoring.store.Store;

/**
 * Data cluster client module.  This class defines dependency bindings
 * for simple data access via DataClient interface.
 */
public class MonitoringStoreModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);

        bind(Store.class).toProvider(CassandraStoreProvider.class)
            .asEagerSingleton();
        expose(Store.class);
    }

}
