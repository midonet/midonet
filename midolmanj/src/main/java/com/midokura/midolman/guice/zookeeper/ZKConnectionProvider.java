/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;

import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkConnectionAwareWatcher;
import com.midokura.util.eventloop.Reactor;

/**
 * A ZKConnection provider which is instantiating a ZKConnection while optionally
 * using a reconnect watcher
 */
public class ZKConnectionProvider implements Provider<ZkConnection> {

    public static final String DIRECTORY_REACTOR_TAG = "directoryReactor";


    @Inject
    ZookeeperConfig config;

    @Inject
    @Named(DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Inject(optional = true)
    ZkConnectionAwareWatcher watcher;

    @Override
    public ZkConnection get() {
        try {
            ZkConnection zkConnection =
                new ZkConnection(
                    config.getZooKeeperHosts(),
                    config.getZooKeeperSessionTimeout(), watcher, reactorLoop);

            if (watcher != null)
                watcher.setZkConnection(zkConnection);
            zkConnection.open();

            return zkConnection;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
