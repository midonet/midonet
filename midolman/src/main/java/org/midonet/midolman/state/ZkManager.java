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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.BadVersionException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.KeeperException.NotEmptyException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  Class that provides data access methods to Zookeeper as a wrapper of
 *  Directory class.
 */
public class ZkManager {

    private final static Logger log =
            LoggerFactory.getLogger(ZkManager.class);

    private final Directory zk;

    private final String basePath;

    public ZkManager(Directory zk, String basePath) {
        this.zk = zk;
        this.basePath = basePath;
    }

    public void asyncGet(String relativePath, DirectoryCallback<byte[]> data,
                         Directory.TypedWatcher watcher) {
        this.zk.asyncGet(relativePath, data, watcher);
    }

    protected StateAccessException processException(Exception ex, String action) {
        if (ex instanceof NodeExistsException) {
            return new StatePathExistsException(
                    "Zookeeper error occurred while " + action + ": " +
                    ex.getMessage(), basePath, (NodeExistsException)ex);
        } else if (ex instanceof NoNodeException) {
            return new NoStatePathException(
                    "Zookeeper error occurred while " + action + ": " +
                    ex.getMessage(), basePath, (NoNodeException)ex);
        } else if (ex instanceof BadVersionException) {
            return new StateVersionException(
                    "Zookeeper error occurred while " + action + ": " +
                    ex.getMessage(), ex);
        } else if (ex instanceof NotEmptyException) {
            return new NodeNotEmptyStateException(
                    "Zookeeper error occurred while " + action + ": " +
                    ex.getMessage(), basePath, (NotEmptyException)ex);
        } else if (ex instanceof KeeperException) {
            return new StateAccessException(
                    "Zookeeper error occurred while " + action + ": " +
                    ex.getMessage(), ex);
        } else if (ex instanceof InterruptedException) {
            return new StateAccessException(
                    "Zookeeper thread interrupted while " + action + ": " +
                    ex.getMessage(), ex);
        }

        log.error("Unexpected exception while " + action, ex);
        throw new RuntimeException(ex);
    }

    public String add(String path, byte[] data, CreateMode mode)
            throws StateAccessException {
        try {
            return this.zk.add(path, data, mode);
        } catch (Exception ex) {
            throw processException(ex, "creating a node at path " + path);
        }
    }

    public Directory getDirectory() {
        return zk;
    }

    public Directory getSubDirectory(String path) throws StateAccessException {
        try {
            return zk.getSubDirectory(path);
        } catch (Exception ex) {
            throw processException(ex, "getting the directory " + path);
        }
    }

    public boolean exists(String path) throws StateAccessException {
        try {
            return zk.has(path);
        } catch (Exception ex) {
            throw processException(
                    ex, "checking whether path " + path + " exists");
        }
    }

    public boolean exists(String path, Watcher watcher)
            throws StateAccessException {
        try {
            return zk.exists(path, watcher);
        } catch (Exception ex) {
            throw processException(
                ex, "checking whether path " + path + " exists");
        }
    }

    public boolean exists(String path, Runnable watcher)
            throws StateAccessException {
        try {
            return zk.exists(path, watcher);
        } catch (Exception ex) {
            throw processException(
                    ex, "checking whether path " + path + " exists");
        }
    }

    public String addPersistent(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path, data, CreateMode.PERSISTENT);
        } catch (Exception ex) {
            throw processException(
                    ex, "adding a persistent node at path " + path);
        }
    }

    public void delete(String path) throws StateAccessException {
        try {
            zk.delete(path);
        } catch (Exception ex) {
            throw processException(ex, "deleting the node at path " + path);
        }
    }

    public byte[] get(String path) throws StateAccessException {
        return get(path, null);
    }

    public byte[] get(String path, Runnable watcher)
            throws StateAccessException {
        try {
            return zk.get(path, watcher);
        }  catch (Exception ex) {
            throw processException(ex, "getting the node at path " + path);
        }
    }

    public Map.Entry<byte[], Integer> getWithVersion(String path, Runnable watcher)
            throws StateAccessException {
        try {
            return zk.getWithVersion(path, watcher);
        }  catch (Exception ex) {
            throw processException(ex, "getting the node at path " + path);
        }
    }

    public Set<String> getChildren(String path) throws StateAccessException {
        return getChildren(path, null);
    }

    public Set<String> getChildren(String path, Runnable watcher)
            throws StateAccessException {
        try {
            return zk.getChildren(path, watcher);
        }  catch (Exception ex) {
            throw processException(ex, "getting the children of " + path);
        }
    }

    public List<OpResult> multi(List<Op> ops) throws StateAccessException {
        try {
            return this.zk.multi(ops);
        } catch (KeeperException ex) {
            throw processException(ex, getMultiErrorMessage(ops, ex));
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while executing multi ops.",
                    e);
        }
    }

    private String getMultiErrorMessage(List<Op> ops, KeeperException ex) {
        List<OpResult> results = ex.getResults();
        if (results == null || results.isEmpty()) {
            return "executing multi ops: " + ex.getMessage();
        }

        StringBuilder msg = new StringBuilder("executing multi ops: ");

        // Use counter to iterate through op and result lists in parallel.
        for (int i = 0; i < results.size(); i++) {
            OpResult result = results.get(i);
            if (result instanceof OpResult.ErrorResult) {
                int errorCode = ((OpResult.ErrorResult)result).getErr();
                if (errorCode != 0) {
                    Op operation = ops.get(i);
                    msg.append("\r\n\t\t");
                    msg.append(operation.getPath());
                    msg.append(" failed with error code: ");
                    msg.append(errorCode);
                }
            }
        }

        return msg.toString();
    }

    public void update(String path, byte[] data) throws StateAccessException {
        update(path, data, -1);
    }

    public void update(String path, byte[] data, int version)
            throws StateAccessException {
        try {
            zk.update(path, data, version);
        } catch (Exception ex) {
            throw processException(ex, "updating the node at path " + path);
        }
    }

    /**
     * Disconnects from the underlying storage.
     */
    public void disconnect() {
        if (zk != null)
            zk.closeConnection();
    }
}
