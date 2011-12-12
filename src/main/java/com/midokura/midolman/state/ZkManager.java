/*
 * @(#)ZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;

import com.midokura.midolman.util.JSONSerializer;
import com.midokura.midolman.util.Serializer;

/**
 * Abstract base class for ZkManagers.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public abstract class ZkManager {

    protected ZkPathManager pathManager = null;
    protected Directory zk = null;

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
    }

    protected <T> byte[] serialize(T obj) throws IOException {
        Serializer<T> s = new JSONSerializer<T>();
        return s.objToBytes(obj);
    }

    protected <T> T deserialize(byte[] obj, Class<T> clazz)
            throws IOException {
        Serializer<T> s = new JSONSerializer<T>();
        return s.bytesToObj(obj, clazz);
    }

    protected Directory getSubDirectory(String path)
            throws StateAccessException {
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

    protected boolean exists(String path) throws StateAccessException {
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

    protected String addPersistent_safe(String path, byte[] data)
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

    protected String addPersistent(String path, byte[] data)
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

    protected String addEphemeral(String path, byte[] data)
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

    protected String addPersistentSequential(String path, byte[] data)
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

    protected byte[] get(String path) throws StateAccessException {
        return get(path, null);
    }

    protected byte[] get(String path, Runnable watcher)
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

    protected Set<String> getChildren(String path) throws StateAccessException {
        return getChildren(path, null);
    }

    protected Set<String> getChildren(String path, Runnable watcher)
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

    protected List<OpResult> multi(List<Op> ops) throws StateAccessException {
        try {
            return this.zk.multi(ops);
        } catch (NodeExistsException e) {
            throw new StatePathExistsException(
                getMultiErrorMessage("ZooKeeper error occurred while " +
                                         "executing multi ops.", ops, e),
                e);
        } catch (NoNodeException e) {
            throw new NoStatePathException(
                getMultiErrorMessage("ZooKeeper error occurred while " +
                                         "executing multi ops.", ops, e), e);
        } catch (KeeperException e) {
            throw new StateAccessException(
                getMultiErrorMessage("ZooKeeper error occurred while " +
                                         "executing multi ops.", ops, e), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while executing multi ops.",
                    e);
        }
    }

    private String getMultiErrorMessage(String message, List<Op> ops,
                                        KeeperException ex)
    {
        message += ex.getMessage();
        List<OpResult> results = ex.getResults();
        if ( results == null ) {
            return message;
        }

        for (int i = 0, resultsSize = results.size(); i < resultsSize; i++) {
            OpResult result = results.get(i);
            Op operation = ops.get(i);

            if  (result instanceof OpResult.ErrorResult) {
                OpResult.ErrorResult errorResult = (OpResult.ErrorResult) result;

                if ( errorResult.getErr() != 0 ) {
                    message += "\r\n\t\t" + operation.getPath()
                        + " failed with error code: " + errorResult.getErr();
                }
            }
        }

        return message;
    }

    protected void update(String path, byte[] data) throws StateAccessException {
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
}
