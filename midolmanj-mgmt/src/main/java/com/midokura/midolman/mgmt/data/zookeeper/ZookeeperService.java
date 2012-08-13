/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import com.midokura.midolman.mgmt.config.ZookeeperConfig;
import com.midokura.midolman.mgmt.data.DataStoreService;
import com.midokura.midolman.state.ZkConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zookeeper service
 */
public class ZookeeperService extends DataStoreService {

    private final static Logger log = LoggerFactory
            .getLogger(ZookeeperService.class);

    private final ZkConnection conn;

    @Inject
    public ZookeeperService(ZkConnection conn) {
        this.conn = conn;
    }

    @Override
    protected void doStart() {
        log.debug("ZookeeperService.doStart: entered.");

        try {
            conn.open();
        } catch (Exception e) {
            throw new RuntimeException("Failed to open ZkConnection", e);
        }
        notifyStarted();

        log.debug("ZookeeperService.doStart: exiting.");
    }

    @Override
    protected void doStop() {
        log.debug("ZookeeperService.doStop: entered.");

        conn.close();
        notifyStopped();

        log.debug("ZookeeperService.doStop: exiting.");
    }

}
