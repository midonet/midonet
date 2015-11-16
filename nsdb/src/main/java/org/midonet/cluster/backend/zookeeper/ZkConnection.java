/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.backend.zookeeper;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.backend.Directory;
import org.midonet.util.eventloop.Reactor;

public class ZkConnection implements Watcher {

    private final static Logger log =
            LoggerFactory.getLogger(ZkConnection.class);
    private ZooKeeper zk;
    private Reactor reactor;
    private String zkHosts;
    private int sessionTimeoutMillis;
    private Watcher watcher;
    private boolean connecting;
    private boolean connected;
    private boolean terminated;

    public ZkConnection(String zkHosts, int sessionTimeoutMillis, Watcher watcher) {
        this.zkHosts = zkHosts;
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        this.watcher = watcher;
        connecting = false;
        connected = false;
        terminated = false;
    }

    public ZkConnection(String zkHosts, int sessionTimeoutMillis,
            Watcher watcher, Reactor reactor) {
        this.zkHosts = zkHosts;
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        this.watcher = watcher;
        this.reactor = reactor;
        connecting = false;
        connected = false;
        terminated = false;
    }

    protected ZkConnection() {
    }

    public void open() throws Exception {
        synchronized (this) {
            if (null == zk) {
                connecting = true;
                zk = new ZooKeeper(zkHosts, sessionTimeoutMillis, this);
            }
            while (connecting) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    log.warn("While opening a new ZooKeeper session", e);
                }
            }
            if (!connected)
                throw new Exception("Cannot open ZooKeeper session.");
        }
        log.info("Connected to ZooKeeper with session {}", zk.getSessionId());
    }

    public void reopen() throws Exception {
        if (terminated) {
            throw new Exception("Cannot reopen ZooKeeper session, "+
                                "instance has been shutdown");
        }
        synchronized (this) {
            _close();
            notifyAll();

            if (null == zk || zk.getState() != ZooKeeper.States.CLOSED)
                return;
            connecting = true;
            connected = false;
            zk = new ZooKeeper(zkHosts, sessionTimeoutMillis, this);
            while (connecting) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    log.warn("While opening a new ZK session after expiration",
                             e);
                }
            }
            if (!connected)
                throw new Exception("Cannot reopen ZooKeeper session.");

            log.info("Reconnected to ZooKeeper with session {}", zk.getSessionId());
            notifyAll();
        }
    }

    private void _close() {
        connecting = false;
        connected = false;
        if (null != zk) {
            try {
                zk.close();
            } catch (InterruptedException e) {
                log.warn("While closing a ZK session", e);
            }
            // Don't reset zk to null. The class is not meant to be re-used.
        }
    }

    public synchronized void close() {
        _close();
        terminated = true;
        if (reactor != null)
            reactor.shutDownNow();
        notifyAll();
    }

    @Override
    public void process(WatchedEvent event) {
        synchronized (this) {
            if (connecting) {
                connecting = false;
                if (event.getState() == KeeperState.SyncConnected)
                    connected = true;
                notifyAll();
            }
        }
        if (null != watcher)
            watcher.process(event);
    }

    public Directory getRootDirectory() {
        return new ZkDirectory(this, "", Ids.OPEN_ACL_UNSAFE, reactor);
    }

    public ZooKeeper getZooKeeper()  {
        synchronized (this) {
            if (zk == null) {
                // what can I do?
                log.error("ZooKeeper session is not open yet");
            }
            return zk;
        }
    }

    public void setWatcher(Watcher watcher) {
        this.watcher = watcher;
    }
}
