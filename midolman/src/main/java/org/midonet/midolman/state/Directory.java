/*
 * Copyright 2011 Midokura KK
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.*;

public interface Directory {

    String add(String relativePath, byte[] data, CreateMode mode)
        throws KeeperException, InterruptedException;

    void ensureHas(String relativePath, byte[] data)
        throws KeeperException, InterruptedException;

    void asyncAdd(String relativePath, byte[] data, CreateMode mode,
                  DirectoryCallback.Add cb);

    void asyncAdd(String relativePath, byte[] data, CreateMode mode);

    void update(String relativePath, byte[] data) throws KeeperException,
            InterruptedException;

    byte[] get(String relativePath, Runnable watcher) throws KeeperException,
            InterruptedException;

    Map.Entry<byte[], Integer> getWithVersion(String relativePath,
            Runnable watcher) throws KeeperException, InterruptedException;

    void asyncGet(String relativePath, DirectoryCallback<byte[]> data,
                  TypedWatcher watcher);

    Set<String> getChildren(String relativePath, Runnable watcher)
            throws KeeperException, InterruptedException;

    void asyncGetChildren(String relativePath,
                          DirectoryCallback<Set<String>> childrenCallback,
                          TypedWatcher watcher);

    boolean has(String relativePath) throws KeeperException,
            InterruptedException;

    void delete(String relativePath) throws KeeperException,
            InterruptedException;

    void asyncDelete(String relativePath, DirectoryCallback.Void callback);

    void asyncDelete(String relativePath);

    Directory getSubDirectory(String relativePath) throws KeeperException;

    List<OpResult> multi(List<Op> ops) throws InterruptedException,
            KeeperException;

    public void asyncMultiPathGet(final Set<String> paths,
                                  final DirectoryCallback<Set<byte[]>> cb);

    long getSessionId();

    void closeConnection();

    // HACK: TypedWatcher is a runnable so that it can be passed to Directory
    // methods that take Runnable 'watchers'. However, the run method should
    // Never be called.
    public interface TypedWatcher extends Runnable {
        void pathDeleted(String path);
        void pathCreated(String path);
        void pathChildrenUpdated(String path);
        void pathDataChanged(String path);
        void connectionStateChanged(Watcher.Event.KeeperState state);
    }

    public static class DefaultTypedWatcher implements TypedWatcher {
        @Override
        public void pathDeleted(String path) {
            run();
        }

        @Override
        public void pathCreated(String path) {
            run();
        }

        @Override
        public void pathChildrenUpdated(String path) {
            run();
        }

        @Override
        public void pathDataChanged(String path) {
            run();
        }

        @Override
        public void connectionStateChanged(Watcher.Event.KeeperState state) {
            // do nothing
        }

        @Override
        public void run() {
        }
    }

    public abstract static class DefaultPersistentWatcher implements TypedWatcher {
        protected ZkConnectionAwareWatcher connectionWatcher;

        public DefaultPersistentWatcher(ZkConnectionAwareWatcher watcher) {
            this.connectionWatcher = watcher;
        }

        protected abstract void _run() throws StateAccessException, KeeperException;

        public abstract String describe();

        @Override
        public final void run() {
            try {
                _run();
            } catch (StateAccessException e) {
                connectionWatcher.handleError(describe(), this, e);
            } catch (KeeperException e) {
                connectionWatcher.handleError(describe(), this, e);
            }
        }

        @Override
        public void pathDeleted(String path) { }

        @Override
        public void pathCreated(String path) { }

        @Override
        public void pathChildrenUpdated(String path) { }

        @Override
        public void pathDataChanged(String path) { }

        @Override
        public void connectionStateChanged(Watcher.Event.KeeperState state) { }

    }
}
