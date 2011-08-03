/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZkConnection implements Watcher {

    private final static Logger log =
            LoggerFactory.getLogger(ZkConnection.class);
    private ZooKeeper zk;
    private String zkHosts;
    private Watcher watcher;
    private boolean connecting;
    private boolean connected;

    public ZkConnection(String zkHosts, Watcher watcher) throws Exception {
        this.zkHosts = zkHosts;
        this.watcher = watcher;
        connecting = false;
        connected = false;
    }

    public synchronized void open() throws Exception {
        if (null == zk) {
            zk = new ZooKeeper(zkHosts, 3000, this);
            connecting = true;
        }
        while (connecting) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (!connected)
            throw new Exception("Cannot open ZooKeeper session.");
        log.debug("Connected to ZooKeeper");
    }

    public synchronized void close() {
        connecting = false;
        connected = false;
        if (null != zk) {
            try {
                zk.close();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // Don't reset zk to null. The class is not meant to be re-used.
        }
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
        return new ZkDirectory(this.zk, "", null);
    }

}
