/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;

import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.services.MidolmanService;
import com.midokura.midolman.services.NetlinkConnectionService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midostore.LocalMidostoreClient;
import com.midokura.midostore.MidostoreClient;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.eventloop.SelectLoop;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class MidolmanModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(ScheduledExecutorService.class)
            .toProvider(ScheduledExecutorServiceProvider.class)
            .asEagerSingleton();

        bind(Reactor.class)
            .toProvider(SelectLoopProvider.class)
            .asEagerSingleton();

        bind(SelectLoop.class)
            .toProvider(SelectLoopProvider.class)
            .asEagerSingleton();

        bind(SelectLoopProvider.class)
            .in(Singleton.class);

        // midolman zookeeper connections
        bind(ZkConnection.class)
            .toProvider(ZKConnectionProvider.class)
            .asEagerSingleton();

        bind(MidolmanConfig.class)
            .toProvider(MidolmanConfigProvider.class)
            .asEagerSingleton();

        bind(Directory.class)
            .toProvider(DirectoryProvider.class);

        bindMidostoreClient();

        bind(OvsDatapathConnection.class)
            .toProvider(OvsDatapathConnectionProvider.class)
            .asEagerSingleton();

        bind(NetlinkConnectionService.class)
            .asEagerSingleton();

        bind(MidolmanService.class)
            .asEagerSingleton();
    }

    protected void bindMidostoreClient() {
        bind(MidostoreClient.class)
            .to(LocalMidostoreClient.class)
            .asEagerSingleton();
    }
}

