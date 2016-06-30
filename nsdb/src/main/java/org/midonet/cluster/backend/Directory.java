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

package org.midonet.cluster.backend;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.Watcher;

public interface Directory {

    String getPath();

    void ensureHas(String relativePath, byte[] data)
        throws KeeperException, InterruptedException;

    String add(String relativePath, byte[] data, CreateMode mode)
        throws KeeperException, InterruptedException;

    void asyncAdd(String relativePath, byte[] data, CreateMode mode,
                  DirectoryCallback<String> callback, Object context);

    void update(String relativePath, byte[] data)
        throws KeeperException, InterruptedException;

    void update(String relativePath, byte[] data, int version)
        throws KeeperException, InterruptedException;

    byte[] get(String relativePath, Runnable watcher)
        throws KeeperException, InterruptedException;

    void asyncGet(String relativePath, DirectoryCallback<byte[]> data,
                  Watcher watcher, Object context);

    Set<String> getChildren(String relativePath, Runnable watcher)
        throws KeeperException, InterruptedException;

    void asyncGetChildren(String relativePath,
                          DirectoryCallback<Collection<String>> callback,
                          Watcher watcher, Object context);

    boolean exists(String relativePath)
        throws KeeperException, InterruptedException;

    boolean exists(String path, Watcher watcher)
        throws KeeperException, InterruptedException;

    void asyncExists(String relativePath, DirectoryCallback<Boolean> callback,
                     Object context);

    void delete(String relativePath)
        throws KeeperException, InterruptedException;

    void asyncDelete(String relativePath, int version,
                     DirectoryCallback<Void> callback, Object context);

    Directory getSubDirectory(String relativePath) throws KeeperException;

    List<OpResult> multi(List<Op> ops)
        throws InterruptedException, KeeperException;

    // HACK: TypedWatcher is a runnable so that it can be passed to Directory
    // methods that take Runnable 'watchers'. However, the run method should
    // Never be called.
    interface TypedWatcher extends Runnable {
        void pathDeleted(String path);
        void pathCreated(String path);
        void pathChildrenUpdated(String path);
        void pathDataChanged(String path);
    }

    class DefaultTypedWatcher implements TypedWatcher {
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
        public void run() {
        }
    }
}
