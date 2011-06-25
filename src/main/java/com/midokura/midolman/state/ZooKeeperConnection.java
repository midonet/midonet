/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import java.io.IOException;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.Reactor;

public class ZooKeeperConnection {
    
    Logger log = LoggerFactory.getLogger(Directory.class);

    ZooKeeper zk;
    Reactor reactor;
    
    public ZooKeeperConnection(String zkHosts, Reactor reactor) throws IOException {
        log.debug("Directory");
        
        zk = new ZooKeeper(zkHosts, 3000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                log.debug("Watcher.process");
                
                switch (event.getState()) {
                case Expired:
                    //TODO: session dead
                    log.error("ZK session died, exiting");
                    System.exit(-1);
                }
                
                if (event.getState() == KeeperState.Disconnected) {
                    log.warn("disconnected from ZooKeeper");
                }
                
                // TODO Auto-generated method stub
                
            }
            
        });
    }
    
    class SubDirImpl implements Directory {
        String basePath;
        
        SubDirImpl(String basePath) {
            this.basePath = basePath;
        }
        
        @Override
        public void add(String relativePath, byte[] data, CreateMode createMode) throws KeeperException, InterruptedException {
            String fqPath = basePath + "/" + relativePath;
            
            zk.create(fqPath, data, null, createMode);
        }

        @Override
        public void update(String relativePath, byte[] data) {
            // TODO Auto-generated method stub
            
        }

        @Override
        public byte[] get(String relativePath) {
            // TODO Auto-generated method stub
            return null;
        }

        public List<String> getChildren(String relativePath) throws KeeperException, InterruptedException {
            String fqPath = basePath + "/" + relativePath;
            return zk.getChildren(fqPath, null);
        }
        @Override
        public Directory getSubDirectory(String path) {
            return new SubDirImpl(basePath + "/" + path);
        }

    }
    
    public Directory getRootDirectory() {
        return new SubDirImpl("");
    }
    
}
