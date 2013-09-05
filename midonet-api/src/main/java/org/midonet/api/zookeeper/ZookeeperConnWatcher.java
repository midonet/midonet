/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.zookeeper;

import com.google.inject.Inject;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkConnection;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.apache.commons.lang.NotImplementedException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zookeeper connection watcher for the API server.
 */
public class ZookeeperConnWatcher implements ZkConnectionAwareWatcher {

    private final static Logger log = LoggerFactory
            .getLogger(ZookeeperConnWatcher.class);

    private ZkConnection conn;
    private final ZookeeperConfig config;

    @Inject
    public ZookeeperConnWatcher(ZookeeperConfig config) {
        this.config = config;
    }

    @Override
    synchronized public void process(WatchedEvent watchedEvent) {
        log.debug("ZookeeperConnWatcher.process: Entered with event {}",
                watchedEvent.getState());

        // The ZK client re-connects automatically. However, after it
        // successfully reconnects, if the session had expired, we need to
        // create a new session.
        if (watchedEvent.getState() == Watcher.Event.KeeperState.Expired
                && conn != null) {
            log.info("Session expired, reconnecting to ZK with a new session");
            try {
                conn.reopen();
            } catch (Exception e) {
                throw new RuntimeException("Zookeeper could not be " +
                        "restarted", e);
            }
        }

        log.debug("ZookeeperConnWatcher.process: Exiting");
    }

    @Override
    public void setZkConnection(ZkConnection conn) {
        this.conn = conn;
    }

    @Override
    public void scheduleOnReconnect(Runnable runnable) {
        throw new NotImplementedException();
    }

    @Override
    public void scheduleOnDisconnect(Runnable runnable) {
        throw new NotImplementedException();
    }

    @Override
    public void handleError(String operationDesc, Runnable retry,
                            KeeperException e) {
        log.warn("handleError(): ignoring: {}", e);
    }

    @Override
    public void handleError(String operationDesc, Runnable retry,
                            StateAccessException e) {
        log.warn("handleError(): ignoring: {}", e);
    }

    @Override
    public void handleTimeout(Runnable runnable) {
        // do nothing
    }

    @Override
    public void handleDisconnect(Runnable runnable) {
        // do nothing
    }
}
