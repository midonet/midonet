/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.datapath;

import javax.inject.Singleton;

import com.google.inject.PrivateModule;

import com.midokura.midolman.services.DatapathConnectionService;
import com.midokura.odp.protos.OvsDatapathConnection;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.eventloop.SelectLoop;


/**
 *
 */
public class DatapathModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(Reactor.class);
        requireBinding(SelectLoop.class);

        bindOvsDatapathConnection();
        expose(OvsDatapathConnection.class);

        bind(DatapathConnectionService.class)
            .asEagerSingleton();
        expose(DatapathConnectionService.class);
    }

    protected void bindOvsDatapathConnection() {
        bind(OvsDatapathConnection.class)
            .toProvider(OvsDatapathConnectionProvider.class)
            //.toProvider(MockOvsDatapathConnectionProvider.class)
            .in(Singleton.class);
    }
}
