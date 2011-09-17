/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher;

public class ZkDirectory implements Directory {

    private ZooKeeper zk;
    private String basePath;
    private List<ACL> acl;

    /**
     * @param zk
     * @param absolutePath
     *            must start with "/"
     * @param acl
     * @param create_mode
     */
    public ZkDirectory(ZooKeeper zk, String basePath, List<ACL> acl) {
        this.zk = zk;
        this.basePath = basePath;
        this.acl = acl;
    }

    @Override
    public String add(String relativePath, byte[] data, CreateMode mode)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        String path = null;
        path = zk.create(absPath, data, acl, mode);
        return path.substring(basePath.length());
    }

    @Override
    public void update(String relativePath, byte[] data)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        zk.setData(absPath, data, -1);
    }

    private class MyWatcher implements Watcher {
        Runnable watcher;

        MyWatcher(Runnable watch) {
            watcher = watch;
        }

        @Override
        public void process(WatchedEvent arg0) {
            watcher.run();
        }
    }

    @Override
    public byte[] get(String relativePath, Runnable watcher)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        return zk.getData(absPath,
            (null == watcher)? null: new MyWatcher(watcher), null);
    }

    @Override
    public Set<String> getChildren(String relativePath, Runnable watcher)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        return new HashSet<String>(zk.getChildren(absPath,
            (null == watcher)? null: new MyWatcher(watcher)));
    }

    @Override
    public boolean has(String relativePath)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        return zk.exists(absPath, null) == null;
    }

    @Override
    public void delete(String relativePath)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        zk.delete(absPath, -1);
    }

    @Override
    public Directory getSubDirectory(String relativePath) {
        return new ZkDirectory(zk, getAbsolutePath(relativePath), null);
    }

    private String getAbsolutePath(String relativePath) {
        if (relativePath.isEmpty())
            return basePath;
        if (!relativePath.startsWith("/"))
            throw new IllegalArgumentException("Path must start with '/'.");
        if (relativePath.endsWith("/"))
            throw new IllegalArgumentException("Path must not end with '/'.");
        return basePath + relativePath;
    }
}
