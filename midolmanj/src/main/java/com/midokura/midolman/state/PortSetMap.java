// Copyright 2012 Midokura Inc.

package com.midokura.midolman.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.packets.IntIPv4;


public class PortSetMap {
    private final static Logger log = LoggerFactory.getLogger(PortSetMap.class);

    private Directory dir;
    private boolean running;
    private Map<UUID, IPv4Set> map;
    private DirectoryWatcher myWatcher;
    private CreateMode createMode;

    public PortSetMap(Directory dir) {
        construct(dir, CreateMode.EPHEMERAL);
    }

    public PortSetMap(Directory dir, CreateMode mode) {
        construct(dir, mode);
    }

    private void construct(Directory dir, CreateMode mode) {
        this.dir = dir;
        this.createMode = mode;
        this.running = false;
        this.map = new HashMap<UUID, IPv4Set>();
        this.myWatcher = new DirectoryWatcher();
    }

    public void stop() {
        this.running = false;
        for (Map.Entry<UUID, IPv4Set> entry : this.map.entrySet())
            entry.getValue().stop();
        this.map.clear();
    }

    public void start() {
        if (!this.running) {
            this.running = true;
            myWatcher.run();
        }
    }

    public IPv4Set get(UUID key) {
        return map.get(key);
    }

    public boolean containsKey(UUID key) {
        return map.containsKey(key);
    }

    public void createPortSet(UUID key)
            throws KeeperException, InterruptedException {
        dir.add(key.toString(), null, CreateMode.PERSISTENT);
        Directory subdir = dir.getSubDirectory(key.toString());
        IPv4Set newSet = new IPv4Set(subdir, createMode);
        map.put(key, newSet);
        newSet.start();
    }

    public void addIPv4AddrToSet(UUID key, IntIPv4 addr) 
            throws KeeperException, InterruptedException,
                   InvalidStateOperationException {
        IPv4Set ipv4Set = map.get(key);
        if (ipv4Set == null) {
            throw new InvalidStateOperationException("No portset with ID " + 
                                                     key.toString());
        }
        ipv4Set.add(addr);
    }

    private class DirectoryWatcher implements Runnable {
        public void run() {
            if (!running) {
                return;
            }

            Set<String> curPaths = null;
            try {
                curPaths = dir.getChildren("/", this);
            } catch (KeeperException e) {
                log.error("DirectoryWatcher.run", e);
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                log.error("DirectoryWatcher.run", e);
                Thread.currentThread().interrupt();
            }
            List<String> cleanupPaths = new LinkedList<String>();
            Set<UUID> curKeys = new HashSet<UUID>();
            for (String path : curPaths) {
                UUID key = UUID.fromString(path);
                curKeys.add(key);
                if (!map.containsKey(key)) {
                    Directory subdir;
                    try {
                        subdir = dir.getSubDirectory(key.toString());
                    } catch (KeeperException e) {
                        log.error("DirectoryWatcher.run", e);
                        throw new RuntimeException(e);
                    }
                    IPv4Set newSet = new IPv4Set(subdir, createMode);
                    map.put(key, newSet);
                    newSet.start();
                }
            }
            Set<UUID> removedKeys = new HashSet<UUID>(map.keySet());
            removedKeys.removeAll(curKeys);
            for (UUID key : removedKeys) {
                map.get(key).stop();
                map.remove(key);
            }
        }
    }

}
