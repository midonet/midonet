/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import java.io.IOException;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZkConnection implements Watcher {

    Logger log = LoggerFactory.getLogger(Directory.class);
    ZooKeeper zk;
    String zkHosts;

	public ZkConnection(String zkHosts) {
		
	}

	public void connect() throws IOException {
		// TODO(Pino): need synchronization here.
		this.zk = new ZooKeeper(this.zkHosts, 3000, this);
	}

	@Override
    public void process(WatchedEvent event) {
        log.debug("process");
        
        switch (event.getState()) {
        case Expired:
            //TODO: session dead
            log.error("ZK session died, exiting");
            System.exit(-1);
        }
        
        if (event.getState() == KeeperState.Disconnected) {
            log.warn("disconnected from ZooKeeper");
        }
	}
	
    public Directory getRootDirectory() {
        return new ZkDirectory(this.zk, "", null);
    }

}
