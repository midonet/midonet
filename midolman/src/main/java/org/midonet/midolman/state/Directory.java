/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.state;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.*;

public interface Directory {

    String getPath();

    String add(String relativePath, byte[] data, CreateMode mode)
        throws KeeperException, InterruptedException;

    void ensureHas(String relativePath, byte[] data)
        throws KeeperException, InterruptedException;

    void asyncAdd(String relativePath, byte[] data, CreateMode mode,
                  DirectoryCallback.Add cb);

    void asyncAdd(String relativePath, byte[] data, CreateMode mode);

    void update(String relativePath, byte[] data) throws KeeperException,
            InterruptedException;

    /**
     * Update with optimistic locking.
     *
     * @param relativePath
     *      Path relative to base path.
     * @param data
     *      Data to write to the node.
     * @param version
     *      Expected node version, obtained from prior call to getWithVersion.
     *      If a concurrent update occurs, it will increment the node's
     *      version and this update will fail with a BadVersionException.
     */
    void update(String relativePath, byte[] data, int version)
            throws KeeperException, InterruptedException;

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

    boolean exists(String path, Watcher watcher) throws KeeperException,
            InterruptedException;

    boolean exists(String path, Runnable watcher)
            throws KeeperException, InterruptedException;

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
