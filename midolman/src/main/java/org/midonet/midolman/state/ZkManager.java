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

import java.util.ArrayList;
import java.util.LinkedList;
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
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.storage.Directory;
import org.midonet.cluster.data.storage.DirectoryCallback;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;
import org.midonet.util.functors.TreeNode;

import static org.midonet.util.functors.TreeNodeFunctors.recursiveBottomUpFold;

/**
 *  Class that provides data access methods to Zookeeper as a wrapper of
 *  Directory class.
 */
public class ZkManager {

    private final static Logger log =
            LoggerFactory.getLogger(ZkManager.class);

    private final Directory zk;

    private final String basePath;

    public static final int ZK_SEQ_NUM_LEN = 10;

    public ZkManager(Directory zk, String basePath) {
        this.zk = zk;
        this.basePath = basePath;
    }

    public void asyncGet(String relativePath, DirectoryCallback<byte[]> data,
                         Directory.TypedWatcher watcher) {
        this.zk.asyncGet(relativePath, data, watcher);
    }

    public void asyncGetChildren(String relativePath,
                          DirectoryCallback<Set<String>> childrenCallback,
                          Directory.TypedWatcher watcher) {
        this.zk.asyncGetChildren(relativePath, childrenCallback, watcher);
    }

    public void asyncAdd(String relativePath, byte[] data, CreateMode mode,
                  DirectoryCallback<String> cb) {
        this.zk.asyncAdd(relativePath, data, mode, cb);
    }

    public void asyncDelete(String relativePath,
                            DirectoryCallback<Void> callback) {
        this.zk.asyncDelete(relativePath, callback);
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

    public void asyncMultiPathGet(final Set<String> paths,
                                  final DirectoryCallback<Set<byte[]>> cb) {
        this.zk.asyncMultiPathGet(paths, cb);
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

    public void asyncExists(String relativePath,
                            DirectoryCallback<Boolean> cb) {
        this.zk.asyncExists(relativePath, cb);
    }

    public String addPersistent_safe(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path, data, CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
            return null;
        } catch (Exception ex) {
            throw processException(
                    ex, "adding a persistent node at path " + path);
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

    /**
     * Creates an ephemeral node if none exists at the specified path.
     * If there already exists one, then it deletes and recreates the
     * node as an ephemeral in order to own it.
     */
    public String ensureEphemeral(String path, byte[] data)
            throws StateAccessException {
        try {
            deleteEphemeral(path);
        } catch (NoStatePathException ignored) { }

        try {
            return zk.add(path, data, CreateMode.EPHEMERAL);
        } catch (Exception ex) {
            throw processException(
                    ex, "adding an ephemeral node at path " + path);
        }
    }

    /**
     * Asynchronously creates an ephemeral node if none exists at the
     * specified path. If there already exists one, then it deletes and
     * recreates the node as an ephemeral in order to own it.
     */
    public void ensureEphemeralAsync(final String path, final byte[] data,
                                     final DirectoryCallback<String> cb) {
        asyncDelete(path, new DirectoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                zk.asyncAdd(path, data, CreateMode.EPHEMERAL, cb);
            }

            @Override
            public void onTimeout() {
                cb.onTimeout();
            }

            @Override
            public void onError(KeeperException e) {
                if (e instanceof NoNodeException)
                    onSuccess(null);
                else
                    cb.onError(e);
            }
        });
    }

    public void deleteEphemeral(String path) throws StateAccessException {
        try {
            zk.delete(path);
        } catch (Exception ex) {
            throw processException(
                    ex, "deleting the ephemeral node at path " + path);
        }
    }

    public String addPersistentSequential(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path + "/", data, CreateMode.PERSISTENT_SEQUENTIAL);
        }  catch (Exception ex) {
            throw processException(
                    ex, "adding a persistent sequential node at path " + path);
        }
    }

    public String addEphemeralSequential(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path + "/", data, CreateMode.EPHEMERAL_SEQUENTIAL);
        } catch (Exception ex) {
            throw processException(
                    ex, "adding an ephemeral sequential node to path ");
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

    public Op getPersistentCreateOp(String path, byte[] data) {
        return Op
                .create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    /**
     * Returns a list of create operations for empty nodes at the
     * specified paths.
     */
    public List<Op> getPersistentCreateOps(String... paths) {
        List<Op> ops = new ArrayList<>(paths.length);
        for (String path : paths) {
            ops.add(Op.create(
                    path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
        return ops;
    }

    public Op getEphemeralCreateOp(String path, byte[] data) {
        log.debug("ZkManager.getEphemeralCreateOp: {}", path);
        return Op.create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
    }

    public Op getDeleteOp(String path) {
        return Op.delete(path, -1);
    }

    /**
     * Returns a list of delete operations for the specified paths.
     * Ignores paths which do not already exist.
     */
    public List<Op> getDeleteOps(String... paths) throws StateAccessException {
        List<Op> ops = new ArrayList<>(paths.length);
        for (String path : paths) {
            if (exists(path))
                ops.add(getDeleteOp(path));
        }
        return ops;
    }

    public Op getSetDataOp(String path, byte[] data) {
        return Op.setData(path, data, -1);
    }

    public List<Op> getRecursiveDeleteOps(String root)
            throws StateAccessException {

        try {
            return recursiveBottomUpFold(new ZKTreeNode(root),
                    new DeleteZookeeperPathOp(), new ArrayList<Op>());
        } catch (StateAccessException ex) {
            throw ex;
        } catch (Exception e) {
            throw new StateAccessException(e);
        }
    }

    public class ZKTreeNode implements TreeNode<String> {
        String value;

        private ZKTreeNode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public List<TreeNode<String>> getChildren() throws Exception {
            return CollectionFunctors.map(
                    ZkManager.this.getChildren(value),
                    new Functor<String, TreeNode<String>>() {
                        @Override
                        public TreeNode<String> apply(String arg0) {
                            return new ZKTreeNode(value + "/" + arg0);
                        }
                    }, new LinkedList<TreeNode<String>>());
        }
    }

    public static class DeleteZookeeperPathOp implements
            org.midonet.util.functors.Functor<String, Op> {
        @Override
        public Op apply(String arg0) {
            return Op.delete(arg0, -1);
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
