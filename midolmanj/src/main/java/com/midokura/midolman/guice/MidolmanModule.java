/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;

import com.google.inject.name.Names;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.services.MidolmanService;
import com.midokura.midolman.services.NetlinkConnectionService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZookeeperConnectionWatcher;
import com.midokura.midostore.LocalMidostoreClient;
import com.midokura.midostore.MidostoreClient;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.eventloop.SelectLoop;
import org.apache.zookeeper.Watcher;

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

        bind(MidolmanConfig.class)
            .toProvider(MidolmanConfigProvider.class)
            .asEagerSingleton();

        bindZookeeperConnection();

        bind(Watcher.class)
            .annotatedWith(Names.named("ZookeeperConnectionWatcher"))
            .to(ZookeeperConnectionWatcher.class);

        bindDirectory();

        bindOvsDatapathConnection();

        bind(NetlinkConnectionService.class)
            .asEagerSingleton();

        bind(MidolmanService.class)
            .asEagerSingleton();
    }

    protected void bindZookeeperConnection() {
        bind(ZkConnection.class)
            .toProvider(ZKConnectionProvider.class)
            .asEagerSingleton();
    }

    protected void bindDirectory() {
        bind(Directory.class)
            .toProvider(DirectoryProvider.class);
    }

    protected void bindOvsDatapathConnection() {
        bind(OvsDatapathConnection.class)
            .toProvider(OvsDatapathConnectionProvider.class)
            .asEagerSingleton();
    }
}

