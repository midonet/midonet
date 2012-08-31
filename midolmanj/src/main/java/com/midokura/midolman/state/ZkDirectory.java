/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.state;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.Reactor;

public class ZkDirectory implements Directory {
    static final Logger log = LoggerFactory.getLogger(ZkDirectory.class);

    public ZkConnection zk;
    private String basePath;
    private List<ACL> acl;
    private Reactor reactor;

    /**
     * @param zk the zookeeper object
     * @param basePath must start with "/"
     * @param acl the list of {@link ACL} the we need to use
     * @param reactor the delayed reactor loop
     */
    public ZkDirectory(ZkConnection zk, String basePath,
                       List<ACL> acl, Reactor reactor) {
        this.zk = zk;
        this.basePath = basePath;
        this.acl = Ids.OPEN_ACL_UNSAFE;
        this.reactor = reactor;
    }

    @Override
    public String toString() {
        return ("ZkDirectory: base=" + basePath);
    }

    @Override
    public String add(String relativePath, byte[] data, CreateMode mode)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        String path = null;
        path = zk.getZooKeeper().create(absPath, data, acl, mode);
        return path.substring(basePath.length());
    }

    @Override
    public void update(String relativePath, byte[] data)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().setData(absPath, data, -1);
    }

    private Watcher wrapCallback(Runnable runnable) {
        if (runnable instanceof TypedWatcher)
            return new MyTypedWatcher((TypedWatcher)runnable);

        return new MyWatcher(runnable);
    }

    private class MyWatcher implements Watcher {
        Runnable watcher;

        MyWatcher(Runnable watch) {
            watcher = watch;
        }

        @Override
        public void process(WatchedEvent arg0) {
            if (null == reactor){
                log.warn("Reactor is null - processing ZK event in ZK thread.");
                watcher.run();
            } else {
                reactor.submit(watcher);
            }
        }
    }

    private class MyTypedWatcher implements Watcher, Runnable {
        TypedWatcher watcher;
        WatchedEvent watchedEvent;

        private MyTypedWatcher(TypedWatcher watcher) {
            this.watcher = watcher;
        }

        @Override
        public void process(WatchedEvent event) {
            if (null == reactor) {
                log.warn("Reactor is null - processing ZK event in ZK thread.");
                dispatchEvent(event, watcher);
            } else {
                watchedEvent = event;
                reactor.submit(this);
            }
        }

        @Override
        public void run() {
            dispatchEvent(watchedEvent, watcher);
        }

        private void dispatchEvent(WatchedEvent event, TypedWatcher typedWatcher) {
            switch (event.getType()) {
                case NodeDeleted:
                    typedWatcher.pathDeleted(event.getPath());
                    break;

                case NodeCreated:
                    typedWatcher.pathCreated(event.getPath());
                    break;

                case NodeChildrenChanged:
                    typedWatcher.pathChildrenUpdated(event.getPath());
                    break;

                case NodeDataChanged:
                    typedWatcher.pathDataChanged(event.getPath());
                    break;

                case None:
                    typedWatcher.pathNoChange(event.getPath());
                    break;
            }
        }
    }

    @Override
    public byte[] get(String relativePath, Runnable watcher)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        return zk.getZooKeeper().getData(absPath, wrapCallback(watcher), null);
    }

    @Override
    public Set<String> getChildren(String relativePath, Runnable watcher)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);

        // path cannot end with / so strip it off
        if (absPath.endsWith("/")) {
            absPath = absPath.substring(0, absPath.length() - 1);
        }

        return
            new HashSet<String>(
                    zk.getZooKeeper().getChildren(absPath, wrapCallback(watcher)));
    }

    @Override
    public boolean has(String relativePath) throws KeeperException,
            InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        return zk.getZooKeeper().exists(absPath, null) != null;
    }

    @Override
    public void delete(String relativePath) throws KeeperException,
            InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().delete(absPath, -1);
    }

    @Override
    public Directory getSubDirectory(String relativePath) {
        return new ZkDirectory(zk, getAbsolutePath(relativePath), null,
                reactor);
    }

    private String getAbsolutePath(String relativePath) {
        if (relativePath.isEmpty())
            return basePath;
        if (!relativePath.startsWith("/"))
            throw new IllegalArgumentException("Path must start with '/'.");
        return basePath + relativePath;
    }

    @Override
    public List<OpResult> multi(List<Op> ops)
        throws InterruptedException, KeeperException {
        return zk.getZooKeeper().multi(ops);
    }

    @Override
    public long getSessionId() {
        return zk.getZooKeeper().getSessionId();
    }
}
