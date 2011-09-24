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

    protected static <T> byte[] serialize(T obj) throws IOException {
        Serializer<T> s = new JSONSerializer<T>();
        return s.objToBytes(obj);
    }

    protected static <T> T deserialize(byte[] obj, Class<T> clazz)
            throws IOException {
        Serializer<T> s = new JSONSerializer<T>();
        return s.bytesToObj(obj, clazz);
    }

    protected Directory getSubDirectory(String path)
            throws StateAccessException {
        try {
            return zk.getSubDirectory(path);
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
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while adding a ephemeral node to path "
                            + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while adding a ephemeral node to the path "
                            + path + ": " + e.getMessage(), e);
        }
    }

    protected String addPersistentSequential(String path, byte[] data)
            throws StateAccessException {
        try {
            return zk.add(path + "/", data, CreateMode.PERSISTENT_SEQUENTIAL);
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
        } catch (KeeperException e) {
            throw new StateAccessException(
                    "ZooKeeper error occurred while executing multi ops. "
                            + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new StateAccessException(
                    "ZooKeeper thread interrupted while executing multi ops.",
                    e);

        }
    }

    protected void update(String path, byte[] data) throws StateAccessException {
        try {
            // Update any version for now.
            zk.update(path, data);
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
