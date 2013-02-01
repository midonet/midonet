// Copyright 2012 Midokura Inc.

package org.midonet.midolman.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.packets.IntIPv4;


public class PortSetMap {
    private final static Logger log = LoggerFactory.getLogger(PortSetMap.class);

    private Directory dir;
    private String basePath;
    private ZkPathManager pathMgr;
    private boolean running;
    private Map<UUID, IPv4Set> map;
    private DirectoryWatcher myWatcher;
    private CreateMode createMode;

    public PortSetMap(Directory dir, String basePath) {
        this(dir, basePath, CreateMode.EPHEMERAL);
    }

    public PortSetMap(Directory dir, String basePath, CreateMode mode) {
        this.dir = dir;
        this.basePath = basePath;
        this.pathMgr = new ZkPathManager(basePath);
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
        String portSetPath = pathMgr.getPortSetPath(key);
        dir.add(portSetPath, null, CreateMode.PERSISTENT);
        Directory subdir = dir.getSubDirectory(portSetPath);
        IPv4Set newSet = new IPv4Set(subdir, createMode);
        map.put(key, newSet);
        newSet.start();
    }

    public Op preparePortSetCreate(UUID key) {
        return Op.create(pathMgr.getPortSetPath(key),
                null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    public Op preparePortSetDelete(UUID key) {
        return Op.delete(pathMgr.getPortSetPath(key), -1);
    }

    public void addIPv4Addr(UUID key, IntIPv4 addr)
            throws StateAccessException {
        IPv4Set ipv4Set = map.get(key);
        if (ipv4Set == null) {
            throw new StateAccessException("No portset with ID " +
                    key.toString());
        }
        ipv4Set.add(addr);

    }

    public void deleteIPv4Addr(UUID key, IntIPv4 addr)
            throws StateAccessException {
        IPv4Set ipv4Set = map.get(key);
        if (ipv4Set == null) {
            throw new StateAccessException("No portset with ID " +
                    key.toString());
        }
        ipv4Set.remove(addr);
    }

    private class DirectoryWatcher implements Runnable {
        public void run() {
            if (!running) {
                return;
            }

            Set<String> curPaths = null;
            try {
                curPaths = dir.getChildren(pathMgr.getPortSetsPath(), this);
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
                        subdir = dir.getSubDirectory(
                                pathMgr.getPortSetPath(key));
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
