/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        conn.setWatcher(connWatcher);
        connWatcher.setZkConnection(conn);

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
