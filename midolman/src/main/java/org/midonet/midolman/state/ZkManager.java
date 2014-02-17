/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

import com.google.inject.Inject;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;
import org.midonet.util.functors.TreeNode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    /**
     * Constructor.
     *
     * @param zk
     *            Directory object.
     */
    @Inject
    public ZkManager(Directory zk, ZookeeperConfig config) {
        this(zk, (config == null) ? null : config.getMidolmanRootKey());
    }

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
                  DirectoryCallback.Add cb) {
        this.zk.asyncAdd(relativePath, data, mode, cb);
    }

    public void asyncAdd(String relativePath, byte[] data, CreateMode mode) {
        this.zk.asyncAdd(relativePath, data, mode);
    }

    public void asyncDelete(String relativePath,
                            DirectoryCallback.Void callback) {
        this.zk.asyncDelete(relativePath, callback);
    }

    public void asyncDelete(String relativePath) {
        this.zk.asyncDelete(relativePath);
    }

    public String add(String relativePath, byte[] data, CreateMode mode)
            throws StateAccessException {
        try {
            return this.zk.add(relativePath, data, mode);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    "path does not exist: " + relativePath + ": " + e.getMessage(),
                    basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while checking if path " +
                            "exists: " + relativePath + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while checking if path " +
                            "exists: " + relativePath + ": " + e.getMessage(), e);
        }
    }

    public void asyncMultiPathGet(final Set<String> paths,
                                  final DirectoryCallback<Set<byte[]>> cb) {
        this.zk.asyncMultiPathGet(paths, cb);
    }

    public Directory getSubDirectory(String path) throws StateAccessException {
        try {
            return zk.getSubDirectory(path);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while getting the directory "
                            + path + ": " + e.getMessage(), basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while getting the directory "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public boolean exists(String path) throws StateAccessException {
        try {
            return zk.has(path);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while checking if path exists: "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while checking if path " +
                            "exists: " + path + ": " + e.getMessage(), e);
        }
    }

    public String addPersistent_safe(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path, data, CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
            return null;
        } catch (NoNodeException e) {
            // Even safe doesn't allow adding to non-existing path.
            throw new NoStatePathException(
                    "ZooKeeper error occurred while adding a persistent node " +
                            "to path " + path + ": " + e.getMessage(),
                    basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding a persistent node" +
                            " to path " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding a persistent " +
                            "node to the path " + path + ": " + e.getMessage(), e);
        }
    }

    public String addPersistent(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path, data, CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    "ZooKeeper error occurred while adding the persistent " +
                            "node to path " + path + ": " + e.getMessage(),
                    basePath, e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while adding the persistent " +
                            "node to path " + path + ": " + e.getMessage(),
                    basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding a persistent " +
                            "node to path " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding a persistent " +
                            "node to the path " + path + ": " + e.getMessage(), e);
        }
    }

    public String addEphemeral(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path, null, CreateMode.EPHEMERAL);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    "ZooKeeper error occurred while adding an ephemeral " +
                            "node to path " + path + ": " + e.getMessage(),
                    basePath, e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while adding an ephemeral " +
                            "node to path " + path + ": " + e.getMessage(),
                    basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding an ephemeral " +
                            "node to path " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding an ephemeral " +
                            "node to the path " + path + ": " + e.getMessage(), e);
        }
    }

    public void deleteEphemeral(String path) throws StateAccessException {
        try {
            zk.delete(path);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while deleting an ephemeral " +
                            "node on path " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while removing an " +
                            "ephemeral node on the path " + path + ": " +
                            e.getMessage(), e);
        }
    }

    public String addPersistentSequential(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path + "/", data, CreateMode.PERSISTENT_SEQUENTIAL);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    "ZooKeeper error occurred while adding a sequential " +
                            "node to path " + path + ": " + e.getMessage(),
                    basePath, e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while adding a sequential " +
                            "node to path " + path + ": " + e.getMessage(),
                    basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding a sequential " +
                            "node to path " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding a sequential " +
                            "node to the path " + path + ": " + e.getMessage(), e);
        }
    }

    public void delete(String path) throws StateAccessException {
        try {
            zk.delete(path);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while deleting a node with path "
                            + path + ": " + e.getMessage(), basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while deleting the path " + path
                            + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while deleting the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public byte[] get(String path) throws StateAccessException {
        return get(path, null);
    }

    public byte[] get(String path, Runnable watcher)
            throws StateAccessException {
        try {
            return zk.get(path, watcher);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while getting a node with path "
                            + path + ": " + e.getMessage(), basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while getting the path " + path
                            + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while getting the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public Map.Entry<byte[], Integer> getWithVersion(String path, Runnable watcher)
            throws StateAccessException {
        try {
            return zk.getWithVersion(path, watcher);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while getting a node with path "
                            + path + ": " + e.getMessage(), basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while getting the path " + path
                            + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while getting the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public Set<String> getChildren(String path) throws StateAccessException {
        return getChildren(path, null);
    }

    public Set<String> getChildren(String path, Runnable watcher)
            throws StateAccessException {
        Set<String> children = null;
        try {
            children = zk.getChildren(path, watcher);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while getting children with path "
                            + path + ": " + e.getMessage(), basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error interrupted while getting the children of "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while getting the " +
                            "children of " + path + ": " + e.getMessage(), e);
        }
        return children;
    }

    public List<OpResult> multiDedup(List<Op> ops) throws StateAccessException {

        Set<String> paths = new HashSet<String>(ops.size());
        List<Op> dedupOps = new ArrayList<Op>(ops.size());
        for(Op op : ops) {
            String path = op.getPath();
            if(!paths.contains(path)) {
                paths.add(path);
                dedupOps.add(op);
            }
        }

        return multi(dedupOps);
    }

    public List<OpResult> multi(List<Op> ops) throws StateAccessException {
        try {
            return this.zk.multi(ops);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    getMultiErrorMessage(ops, e), basePath, e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    getMultiErrorMessage(ops, e), basePath, e);
        } catch (KeeperException e) {
            throw new StateAccessException(getMultiErrorMessage(ops, e), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while executing multi ops.",
                    e);
        }
    }

    private String getMultiErrorMessage(List<Op> ops, KeeperException ex) {
        StringBuilder messageBuilder = new StringBuilder(
                "ZooKeeper error occurred while executing multi ops: ");
        messageBuilder.append(ex.getMessage());

        List<OpResult> results = ex.getResults();
        if (results == null) {
            return messageBuilder.toString();
        }

        for (int i = 0, resultsSize = results.size(); i < resultsSize; i++) {
            OpResult result = results.get(i);
            Op operation = ops.get(i);

            if (result instanceof OpResult.ErrorResult) {
                OpResult.ErrorResult errorResult =
                        (OpResult.ErrorResult) result;

                if (errorResult.getErr() != 0) {
                    messageBuilder.append("\r\n\t\t")
                                  .append(operation.getPath())
                                  .append(" failed with error code: ")
                                  .append(errorResult.getErr());
                }
            }
        }

        return messageBuilder.toString();
    }

    public void update(String path, byte[] data) throws StateAccessException {
        try {
            // Update any version for now.
            zk.update(path, data);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while updating the path " + path
                            + ": " + e.getMessage(), basePath,  e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while updating the path " + path
                            + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while updating the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public Op getPersistentCreateOp(String path, byte[] data) {
        return Op
                .create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    public Op getEphemeralCreateOp(String path, byte[] data) {
        log.debug("ZkManager.getEphemeralCreateOp", path);
        return Op.create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
    }

    public Op getPersistentSequentialCreateOp(String path, byte[] data) {
        log.debug("ZkManager.getPersistentSequentialCreateOp", path);
        return Op.create(path, data, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL);
    }

    public Op getDeleteOp(String path) {
        return Op.delete(path, -1);
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
