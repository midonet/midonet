/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.midolman.state.ZkConnection;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;

/**
 * ZkConnection provider
 */
public class ZkConnectionProvider implements Provider<ZkConnection>  {
    @Inject
    ZkConnectionAwareWatcher connWatcher;

    private final static Logger log = LoggerFactory
            .getLogger(ZkConnectionProvider.class);

    private final ZookeeperConfig config;

    @Inject
    public ZkConnectionProvider(ZookeeperConfig config) {
        this.config = config;
    }

    @Override
    public ZkConnection get() {
        log.debug("ZkConnectionProvider.get: entered");

        ZkConnection conn = new ZkConnection(config.getZkHosts(),
            config.getZkSessionTimeout(), null);
        conn.setWatcher(connWatcher);
        connWatcher.setZkConnection(conn);

        // When mocking, don't open the connection.  Can't return null here
        // because other modules don't allow null.
        try {
            conn.open();
        } catch (Exception e) {
            throw new RuntimeException("ZK connection could not be opened.", e);
        }

        log.debug("ZkConnectionProvider.get: exiting.");
        return conn;
    }
}
