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
import org.apache.zookeeper.KeeperException.NoNodeException;
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
    public String add(String relativePath, byte[] data, CreateMode mode) {
        String absPath = getAbsolutePath(relativePath);
        String path = null;
        try {
            path = zk.create(absPath, data, acl, mode);
        } catch (InterruptedException e1) {
        } catch (KeeperException e2) {
        }
        return path.substring(basePath.length());
    }

    @Override
    public void update(String relativePath, byte[] data) {
        String absPath = getAbsolutePath(relativePath);
        try {
            zk.setData(absPath, data, -1);
        } catch (InterruptedException e1) {
        } catch (KeeperException e2) {
        }
    }

    private class MyWatcher implements Watcher {
        Runnable watcher;

        MyWatcher(Runnable watch) {
            watcher = watch;
        }

        @Override
        public void process(WatchedEvent arg0) {
            // TODO(pino): check the event type.
            watcher.run();
        }
    }

    @Override
    public byte[] get(String relativePath, Runnable watcher) {
        String absPath = getAbsolutePath(relativePath);
        try {
            return zk.getData(absPath, new MyWatcher(watcher), null);
        } catch (InterruptedException e1) {
        } catch (KeeperException e2) {
        }
        return null;
    }

    @Override
    public Set<String> getChildren(String relativePath, Runnable watcher) {
        String absPath = getAbsolutePath(relativePath);
        try {
            return new HashSet<String>(zk.getChildren(
                    absPath, new MyWatcher(watcher)));
        } catch (InterruptedException e1) {
        } catch (KeeperException e2) {
        }
        return null;
    }

    @Override
    public boolean has(String relativePath) {
        String absPath = getAbsolutePath(relativePath);
        try {
            return zk.exists(absPath, null) == null;
        } catch (InterruptedException e1) {
        } catch (KeeperException e2) {
        }
        return false;
    }

    @Override
    public void delete(String relativePath) {
        String absPath = getAbsolutePath(relativePath);
        try {
            zk.delete(absPath, -1);
        } catch (InterruptedException e1) {
        } catch (KeeperException e2) {
        }
    }

    @Override
    public Directory getSubDirectory(String relativePath)
            throws NoNodeException {
        String absPath = getAbsolutePath(relativePath);
        return new ZkDirectory(zk, absPath, null);
    }

    private String getAbsolutePath(String relativePath) {
        if (relativePath.length() > 1) {
            if (basePath.length() > 1) {
                return basePath + relativePath;
            } else {
                return relativePath;
            }
        } else {
            return basePath;
        }
    }

}
