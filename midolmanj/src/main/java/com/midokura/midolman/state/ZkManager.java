/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.util.JSONSerializer;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;
import com.midokura.util.functors.TreeNode;
import static com.midokura.util.functors.TreeNodeFunctors.recursiveBottomUpFold;

/**
 * Abstract base class for ZkManagers.
 */
public class ZkManager {

    private final static Logger log = LoggerFactory.getLogger(ZkManager.class);
    protected ZkPathManager pathManager = null;
    protected Directory zk = null;
    protected ZkConfigSerializer serializer;

    /**
     * Constructor.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            Path to set as the base.
     */
    public ZkManager(Directory zk, String basePath) {
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
        this.serializer = new ZkConfigSerializer(new JSONSerializer());
    }

    public Directory getSubDirectory(String path) throws StateAccessException {
        try {
            return zk.getSubDirectory(path);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while getting the directory "
                            + path + ": " + e.getMessage(), e);
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
                    "ZooKeeper thread interrupted while checking if path exists: "
                            + path + ": " + e.getMessage(), e);
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
                    "ZooKeeper error occurred while adding a persistent node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding a persistent node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding a persistent node to the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public String addPersistent(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path, data, CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    "ZooKeeper error occurred while adding the persistent node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while adding the persistent node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding a persistent node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding a persistent node to the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public String addEphemeral(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path, null, CreateMode.EPHEMERAL);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    "ZooKeeper error occurred while adding an ephemeral node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while adding an ephemeral node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding an ephemeral node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding an ephemeral node to the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public String addPersistentSequential(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path + "/", data, CreateMode.PERSISTENT_SEQUENTIAL);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                    "ZooKeeper error occurred while adding a sequential node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while adding a sequential node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding a sequential node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding a sequential node to the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    public void delete(String path) throws StateAccessException {
        try {
            zk.delete(path);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while deleting a node with path "
                            + path + ": " + e.getMessage(), e);
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
                            + path + ": " + e.getMessage(), e);
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
                            + path + ": " + e.getMessage(), e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error interrupted while getting the children of "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while getting the children of "
                            + path + ": " + e.getMessage(), e);
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
            throw new StatePathExistsException(getMultiErrorMessage(
                    "ZooKeeper error occurred while " + "executing multi ops.",
                    ops, e), e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(getMultiErrorMessage(
                    "ZooKeeper error occurred while " + "executing multi ops.",
                    ops, e), e);
        } catch (KeeperException e) {
            throw new StateAccessException(getMultiErrorMessage(
                    "ZooKeeper error occurred while " + "executing multi ops.",
                    ops, e), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while executing multi ops.",
                    e);
        }
    }

    private String getMultiErrorMessage(String message, List<Op> ops,
            KeeperException ex) {
        message += ex.getMessage();
        List<OpResult> results = ex.getResults();
        if (results == null) {
            return message;
        }

        for (int i = 0, resultsSize = results.size(); i < resultsSize; i++) {
            OpResult result = results.get(i);
            Op operation = ops.get(i);

            if (result instanceof OpResult.ErrorResult) {
                OpResult.ErrorResult errorResult = (OpResult.ErrorResult) result;

                if (errorResult.getErr() != 0) {
                    message += "\r\n\t\t" + operation.getPath()
                            + " failed with error code: "
                            + errorResult.getErr();
                }
            }
        }

        return message;
    }

    public void update(String path, byte[] data) throws StateAccessException {
        try {
            // Update any version for now.
            zk.update(path, data);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                    "ZooKeeper error occurred while updating the path " + path
                            + ": " + e.getMessage(), e);
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
        log.debug("ZkManager.getPersistentCreateOp entered: path={}", path);
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
        log.debug("ZkManager.getDeleteOp entered: path={}", path);
        return Op.delete(path, -1);
    }

    public Op getSetDataOp(String path, byte[] data) {
        log.debug("ZkManager.getDeleteOp entered: path={}", path);
        return Op.setData(path, data, -1);
    }

    protected List<Op> getRecursiveDeleteOps(String root)
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

    protected class ZKTreeNode implements TreeNode<String> {
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

    protected static class DeleteZookeeperPathOp implements
            com.midokura.util.functors.Functor<String, Op> {
        @Override
        public Op apply(String arg0) {
            return Op.delete(arg0, -1);
        }
    }
}
