/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Singleton;

import com.google.inject.PrivateModule;

import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectLoop;


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
            .in(Singleton.class);
    }
}
