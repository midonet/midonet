/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.zookeeper;

import com.google.inject.PrivateModule;

import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.util.eventloop.Reactor;

/**
 *
 */
public class ZookeeperConnectionModule extends PrivateModule {
    @Override
    protected void configure() {

        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);
        requireBinding(Reactor.class);

        bindZookeeperConnection();
        bindDirectory();

        expose(Directory.class);
    }

    protected void bindDirectory() {
        bind(Directory.class)
            .toProvider(DirectoryProvider.class)
            .asEagerSingleton();
    }

    protected void bindZookeeperConnection() {
        bind(ZkConnection.class)
            .toProvider(ZKConnectionProvider.class)
            .asEagerSingleton();
    }
}
