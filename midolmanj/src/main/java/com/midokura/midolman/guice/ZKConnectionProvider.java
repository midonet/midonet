/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.Inject;
import com.google.inject.Provider;

import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.util.eventloop.Reactor;
import org.apache.zookeeper.Watcher;

import javax.inject.Named;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class ZKConnectionProvider implements Provider<ZkConnection> {

    @Inject
    MidolmanConfig config;

    @Inject
    Reactor reactorLoop;

    @Inject
    @Named("ZookeeperConnectionWatcher")
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
