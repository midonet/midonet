/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;

import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.state.ZkConnection;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.util.eventloop.Reactor;

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
