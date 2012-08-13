/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.util.eventloop.Reactor;
import org.apache.zookeeper.Watcher;

import javax.inject.Named;

/**
 * A ZKConnection provider which is instantiating a ZKConnection while optionally
 * using a reconnect watcher
 */
public class ZKConnectionProvider implements Provider<ZkConnection> {

    public static final String WATCHER_NAME_TAG = "ZookeeperConnectionWatcher";

    @Inject
    ZookeeperConfig config;

    @Inject(optional = true)
    Reactor reactorLoop;

    @Inject(optional = true)
    @Named(WATCHER_NAME_TAG)
    Watcher watcher;

    @Override
    public ZkConnection get() {
        try {
            ZkConnection zkConnection =
                new ZkConnection(
                    config.getZooKeeperHosts(),
                    config.getZooKeeperSessionTimeout(), watcher, reactorLoop);

            zkConnection.open();

            return zkConnection;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
