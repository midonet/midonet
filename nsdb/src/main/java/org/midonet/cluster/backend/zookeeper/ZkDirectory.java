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

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    static final Logger log = LoggerFactory.getLogger(ZkDirectory.class);

    public ZkConnection zk;
    private String basePath;
    private List<ACL> acl;
    private Reactor reactor;

    /**
     * @param zk       the zookeeper object
     * @param basePath must start with "/"
     * @param acl      the list of {@link ACL} the we need to use
     * @param reactor  the delayed reactor loop
     */
    public ZkDirectory(ZkConnection zk, String basePath,
                       List<ACL> acl, Reactor reactor) {
        this.zk = zk;
        this.basePath = basePath;
        this.acl = acl;
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
            if (!this.has(relativePath)) {
                this.add(relativePath, data, CreateMode.PERSISTENT);
            }
        } catch (KeeperException.NodeExistsException e) { /* node was there */ }
    }

    @Override
    public void asyncAdd(String relativePath, final byte[] data,
                         CreateMode mode, final DirectoryCallback<String> cb) {

        final String absPath = getAbsolutePath(relativePath);

        zk.getZooKeeper().create(
            absPath, data, acl, mode,
            new AsyncCallback.StringCallback() {

                @Override
                public void processResult(int rc, String path,
                                          Object ctx, String name) {
                    KeeperException.Code code = KeeperException.Code.get(rc);
                    switch (code) {
                        case OK:
                            cb.onSuccess(name.substring(basePath.length()));
                            break;
                        default:
                            cb.onError(KeeperException.create(code, path));
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

                case None:
                    typedWatcher.connectionStateChanged(event.getState());
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
    public Map.Entry<byte[], Integer> getWithVersion(String relativePath, Runnable watcher)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(relativePath);
        Stat returnStat = new Stat();

        byte[] data = zk.getZooKeeper().getData(absPath, wrapCallback(watcher), returnStat);
        int version = returnStat.getVersion();

        return new AbstractMap.SimpleEntry<>(data, version);
    }

    @Override
    public void asyncGet(String relativePath, final DirectoryCallback<byte[]> dataCallback, TypedWatcher watcher) {
        zk.getZooKeeper().getData(
            getAbsolutePath(relativePath), wrapCallback(watcher),
            new AsyncCallback.DataCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          byte[] data, Stat stat) {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        dataCallback.onSuccess(data);
                    } else {
                        dataCallback.onError(KeeperException.create(
                            KeeperException.Code.get(rc), path));
                    }
                }
            },
            null);
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
    public boolean exists(String path, Watcher watcher)
            throws KeeperException, InterruptedException {
        String absPath = getAbsolutePath(path);
        return zk.getZooKeeper().exists(absPath, watcher) != null;
    }

    @Override
    public boolean exists(String path, Runnable watcher)
            throws KeeperException, InterruptedException {
        return exists(path, wrapCallback(watcher));
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
    public void asyncDelete(String relativePath, final DirectoryCallback<Void> callback) {
        String absPath = getAbsolutePath(relativePath);
        zk.getZooKeeper().delete(absPath, -1, new AsyncCallback.VoidCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx) {
                if (rc == KeeperException.Code.OK.intValue()) {
                    callback.onSuccess(null);
                } else {
                    callback.onError(KeeperException.create(KeeperException.Code.get(rc), path));
                }
            }
        }, null);
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
    public void closeConnection() {
        log.info("Closing the Zookeeper connection.");
        zk.close();
    }
}
