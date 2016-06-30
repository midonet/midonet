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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.backend.Directory;
import org.midonet.cluster.backend.DirectoryCallback;
import org.midonet.util.eventloop.Reactor;

public class ZkDirectory implements Directory {
    private static final Logger log = LoggerFactory.getLogger(ZkDirectory.class);

    public ZkConnection zk;
    private String basePath;
    private final List<ACL> acl = Ids.OPEN_ACL_UNSAFE;
    private Reactor reactor;

    public ZkDirectory(ZkConnection zk, String basePath, Reactor reactor) {
        this.zk = zk;
        this.basePath = basePath;
        this.reactor = reactor;
    }

    @Override
    public String getPath() {
        return basePath;
    }

    @Override
    public String toString() {
        return ("ZkDirectory: base=" + basePath);
    }

    @Override
    public String add(String relativePath, byte[] data, CreateMode mode)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        String path = zk.getZooKeeper().create(absPath, data, acl, mode);
        return path.substring(basePath.length());
    }

    @Override
    public void ensureHas(String relativePath, byte[] data)
            throws KeeperException, InterruptedException {
        try {
            if (!this.exists(relativePath)) {
                this.add(relativePath, data, CreateMode.PERSISTENT);
            }
        } catch (KeeperException.NodeExistsException e) { /* node was there */ }
    }

    @Override
    public void asyncAdd(String relativePath, final byte[] data,
                         CreateMode mode, final DirectoryCallback<String> cb,
                         Object object) {

        final String absPath = getAbsolutePath(relativePath);

        zk.getZooKeeper().create(
            absPath, data, acl, mode,
            new AsyncCallback.StringCallback() {
                @Override
                public void processResult(int rc, String path,
                                          Object ctx, String name) {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        cb.onSuccess(name.substring(basePath.length()), null,
                                     object);
                    } else {
                        cb.onError(KeeperException.create(
                            KeeperException.Code.get(rc), path), ctx);
                    }
                }
            },
            null);
    }

    @Override
    public void update(String relativePath, byte[] data)
        throws KeeperException, InterruptedException {
        update(relativePath, data, -1);
    }

    @Override
    public void update(String relativePath, byte[] data, int version)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().setData(absPath, data, version);
    }

    private Watcher wrapCallback(Runnable runnable) {
        if (null == runnable)
            return null;
        if (runnable instanceof TypedWatcher)
            return new MyTypedWatcher((TypedWatcher) runnable);

        return new MyWatcher(runnable);
    }

    private class MyWatcher implements Watcher {
        Runnable watcher;

        MyWatcher(Runnable watch) {
            watcher = watch;
        }

        @Override
        public void process(WatchedEvent arg0) {
            if (arg0.getType() == Event.EventType.None)
                return;

            if (null == reactor) {
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
    public void asyncGet(String relativePath,
                         DirectoryCallback<byte[]> dataCallback,
                         Watcher watcher, Object context) {
        zk.getZooKeeper().getData(
            getAbsolutePath(relativePath), watcher,
            new AsyncCallback.DataCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          byte[] data, Stat stat) {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        dataCallback.onSuccess(data, stat, ctx);
                    } else {
                        dataCallback.onError(KeeperException.create(
                            KeeperException.Code.get(rc), path), ctx);
                    }
                }
            }, context);
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
            new HashSet<>(
                zk.getZooKeeper().getChildren(absPath, wrapCallback(watcher)));
    }

    @Override
    public void asyncGetChildren(String relativePath,
                                 DirectoryCallback<Collection<String>> callback,
                                 Watcher watcher, Object context) {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().getChildren(
            absPath, watcher, new AsyncCallback.Children2Callback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          List<String> children, Stat stat) {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        callback.onSuccess(children, stat, ctx);
                    } else {
                        callback.onError(KeeperException.create(
                            KeeperException.Code.get(rc), path), ctx);
                    }
                }
            }, context);
    }

    @Override
    public boolean exists(String path, Watcher watcher)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(path);
        return zk.getZooKeeper().exists(absPath, watcher) != null;
    }

    @Override
    public boolean exists(String relativePath) throws KeeperException,
                                                   InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        return zk.getZooKeeper().exists(absPath, null) != null;
    }

    @Override
    public void asyncExists(String relativePath,
                            DirectoryCallback<Boolean> callback,
                            Object context) {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().exists(
            absPath, null, new AsyncCallback.StatCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          Stat stat) {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        callback.onSuccess(true, stat, ctx);
                    } else if (rc == KeeperException.Code.NONODE.intValue()) {
                        callback.onSuccess(false, null, ctx);
                    } else {
                        callback.onError(KeeperException.create(
                            KeeperException.Code.get(rc), path), ctx);
                    }
                }
            }, context);
    }

    @Override
    public void delete(String relativePath) throws KeeperException,
                                                   InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().delete(absPath, -1);
    }

    @Override
    public void asyncDelete(String relativePath, int version,
                            DirectoryCallback<Void> callback,
                            Object context) {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().delete(
            absPath, version, new AsyncCallback.VoidCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx) {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        callback.onSuccess(null, null, ctx);
                    } else {
                        callback.onError(KeeperException.create(
                            KeeperException.Code.get(rc), path), ctx);
                    }
                }
            }, context);
    }

    @Override
    public Directory getSubDirectory(String relativePath) {
        return new ZkDirectory(zk, getAbsolutePath(relativePath), reactor);
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
}
