/**
 * ZkDumper.java - simple command line utility to dump data from ZooKeeper.
 *
 * Copyright 2011 Midokura Inc.
 */

package com.midokura.midolman.state;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Semaphore;

public class ZkDumper {

    private final static Logger log = 
	LoggerFactory.getLogger(ZkDumper.class);

    static ZooKeeper zk;
    static final Semaphore available = new Semaphore(0);

    public static int main(String args[]) {
        int zkPort = 2181;      // FIXME: Get from args
        String zkHost = "localhost";    // Ditto

        try {
            setupZKConnection(zkHost, zkPort);
        } catch (Exception e) {
            log.error("Failed to establish ZooKeeper connection: {}", e);
            System.exit(-1);
        }

        try {
            dumpSubTree("/", 0);
        } catch (Exception e) {
            log.error("Error dumping tree: {}", e);
            System.exit(-1);
        }

        try {
            zk.close();
        } catch (Exception e) {
            log.error("Error closing connection: {}", e);
            System.exit(-1);
        }

        return 0;
    }

    private static void setupZKConnection(String host, int port) 
                throws Exception {
        int magic = 3000;  // FIXME
        zk = new ZooKeeper(host+":"+port, magic, 
                new Watcher() {
                    @Override
                    public synchronized void process(WatchedEvent event) {
                        if (event.getState() == KeeperState.Disconnected) {
                            log.warn("Disconnected from ZooKeeper");
                            System.exit(-1);
                        } else if (event.getState() == KeeperState.SyncConnected) {
                            available.release();
                        } else if (event.getState() == KeeperState.Expired) {
                            log.warn("Session expired");
                            System.exit(-1);
                        }
                    }
                });
        
        available.acquire();
    }

    static void dumpSubTree(String path, int level) throws Exception {
        List<String> children = zk.getChildren(path, false);
        for (String child : children) {
            String childPath = path + (path.endsWith("/") ? "" : "/") + child;
            byte[] data = zk.getData(childPath, false, null);
            String dataStr = (data == null) ? null : new String(data);
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<level; i++) {
                sb.append('\t');
            }
            sb.append(child).append(" => ").append(dataStr);
            System.out.println(sb.toString());
            dumpSubTree(childPath, level+1);
        }
    }
}
