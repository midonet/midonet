/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import com.midokura.midolman.state.ZkConnection;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zookeeper connection watcher for the API server.
 */
public class ZookeeperConnWatcher implements Watcher {

    private final static Logger log = LoggerFactory
            .getLogger(ZookeeperConnWatcher.class);

    private ZkConnection conn = null;

    public ZookeeperConnWatcher(ZkConnection conn) {
        this.conn = conn;
    }

    @Override
    synchronized public void process(WatchedEvent watchedEvent) {
        log.debug("ConnectionWatcher.process: Entered with event {}",
                watchedEvent.getState());

        // The ZK client re-connects automatically. However, after it
        // successfully reconnects, if the session had expired, we need to
        // create a new session.
        if (watchedEvent.getState() == Watcher.Event.KeeperState.Expired
                && conn != null) {
            conn.close();
            conn = null;
        }

        log.debug("ConnectionWatcher.process: Exiting");
    }

}
