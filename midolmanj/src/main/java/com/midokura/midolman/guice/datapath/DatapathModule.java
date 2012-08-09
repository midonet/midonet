/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.datapath;

import javax.inject.Singleton;

import com.google.inject.PrivateModule;

import com.midokura.midolman.services.DatapathConnectionService;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.util.eventloop.Reactor;

/**
 *
 */
public class DatapathModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(Reactor.class);

        bindOvsDatapathConnection();
        expose(OvsDatapathConnection.class);

        bind(DatapathConnectionService.class)
            .asEagerSingleton();
        expose(DatapathConnectionService.class);
    }

    protected void bindOvsDatapathConnection() {
        bind(OvsDatapathConnection.class)
            .toProvider(OvsDatapathConnectionProvider.class)
            .in(Singleton.class);
    }
}
