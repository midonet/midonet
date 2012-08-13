/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.midolman.mgmt.config.ZookeeperConfig;
import com.midokura.midolman.mgmt.data.zookeeper.ZookeeperConnWatcher;
import com.midokura.midolman.state.ZkConnection;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZkConnection provider
 */
public class ZkConnectionProvider implements Provider<ZkConnection> {

    private final static Logger log = LoggerFactory
            .getLogger(ZkConnectionProvider.class);

    private final ZookeeperConfig config;

    @Inject
    public ZkConnectionProvider(ZookeeperConfig config) {
        this.config = config;
    }

    @Override
    public ZkConnection get() {
        log.debug("ZkConnectionProvider.get: entered.");

        ZkConnection conn = new ZkConnection(config.getZooKeeperHosts(),
            config.getZooKeeperSessionTimeout(), null);
        Watcher watcher = new ZookeeperConnWatcher(conn);
        conn.setWatcher(watcher);

        log.debug("ZkConnectionProvider.get: exiting.");
        return conn;
    }
}
