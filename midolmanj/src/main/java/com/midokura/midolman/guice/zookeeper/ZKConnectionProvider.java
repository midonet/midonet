/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.zookeeper;

import javax.inject.Named;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.apache.zookeeper.Watcher;

import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkConnectionAwareWatcher;
import com.midokura.util.eventloop.Reactor;

/**
 * A ZKConnection provider which is instantiating a ZKConnection while optionally
 * using a reconnect watcher
 */
public class ZKConnectionProvider implements Provider<ZkConnection> {

    public static final String WATCHER_NAME_TAG = "ZookeeperConnectionWatcher";

    public static final String DIRECTORY_REACTOR_TAG = "directoryReactor";


    @Inject
    ZookeeperConfig config;

    @Inject
    @Named(DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Inject(optional = true)
    @Named(WATCHER_NAME_TAG)
    ZkConnectionAwareWatcher watcher;

    @Override
    public ZkConnection get() {
        try {
            ZkConnection zkConnection =
                new ZkConnection(
                    config.getZooKeeperHosts(),
                    config.getZooKeeperSessionTimeout(), watcher, reactorLoop);

            watcher.setZkConnection(zkConnection);
            zkConnection.open();

            return zkConnection;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
