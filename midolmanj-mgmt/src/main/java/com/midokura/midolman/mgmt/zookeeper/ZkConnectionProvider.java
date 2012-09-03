/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.midolman.state.ZkConnection;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZkConnection provider
 */
public class ZkConnectionProvider implements Provider<ZkConnection>  {

    private final static Logger log = LoggerFactory
            .getLogger(ZkConnectionProvider.class);

    private final ExtendedZookeeperConfig config;

    @Inject
    public ZkConnectionProvider(ExtendedZookeeperConfig config) {
        this.config = config;
    }

    @Override
    public ZkConnection get() {
        log.debug("ZkConnectionProvider.get: entered.  Mock? ",
                config.getUseMock());

        ZkConnection conn = new ZkConnection(config.getZooKeeperHosts(),
            config.getZooKeeperSessionTimeout(), null);
        Watcher watcher = new ZookeeperConnWatcher(conn, config);
        conn.setWatcher(watcher);

        // When mocking, don't open the connection.  Can't return null here
        // because other modules don't allow null.
        if(!config.getUseMock()) {
            try {
                conn.open();
            } catch (Exception e) {
                throw new RuntimeException("ZK connection could not be opened.",
                        e);
            }
        }

        log.debug("ZkConnectionProvider.get: exiting.");
        return conn;
    }
}
