/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.zookeeper;

import com.google.inject.Inject;

import org.apache.commons.lang.NotImplementedException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.event.api.NsdbEvent;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkConnection;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;

/**
 * Zookeeper connection watcher for the API server.
 */
public class ZookeeperConnWatcher implements ZkConnectionAwareWatcher {

    private final static Logger log = LoggerFactory
            .getLogger(ZookeeperConnWatcher.class);
    private final static NsdbEvent apiNsdbEvent = new NsdbEvent();

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

        if (watchedEvent.getState() == Event.KeeperState.SyncConnected) {
            apiNsdbEvent.connect();
        }else if (watchedEvent.getState() == Event.KeeperState.Disconnected) {
            apiNsdbEvent.disconnect();
        }

        // The ZK client re-connects automatically. However, after it
        // successfully reconnects, if the session had expired, we need to
        // create a new session.
        else if (watchedEvent.getState() == Watcher.Event.KeeperState.Expired
                && conn != null) {
            log.info("Session expired, reconnecting to ZK with a new session");
            apiNsdbEvent.connExpire();
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
    public ZkConnection getZkConnection() {
        return conn;
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
