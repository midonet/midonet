/*
 * @(#)GreZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

/**
 * Class to manage the GRE ZooKeeper data.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class GreZkManager extends ZkManager {

    public static class GreKey {

        public GreKey() {
            super();
        }

        public GreKey(UUID bridgeId) {
            super();
            this.bridgeId = bridgeId;
        }

        public UUID bridgeId;
    }

    /**
     * Initializes a GreZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     * 
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public GreZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public GreZkManager(ZooKeeper zk, String basePath) {
        this(new ZkDirectory(zk, "", Ids.OPEN_ACL_UNSAFE), basePath);
    }

    private int extractGreKeyFromPath(String path) {
        int idx = path.lastIndexOf('/');
        return Integer.parseInt(path.substring(idx + 1));
    }

    /**
     * Constructs a list of operations to perform in a gre update.
     * 
     * @param gre
     *            GreKey ZooKeeper entry to update.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareGreUpdate(ZkNodeEntry<Integer, GreKey> gre)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.setData(pathManager.getGreKeyPath(gre.key),
                    serialize(gre.value), -1));

        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize GRE",
                    e, GreKey.class);
        }
        return ops;
    }

    public ZkNodeEntry<Integer, GreKey> get(int key) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.get(pathManager.getGreKeyPath(key), null);
        GreKey gre = null;
        try {
            gre = deserialize(data, GreKey.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize chain " + key + " to GreKey", e,
                    GreKey.class);
        }
        return new ZkNodeEntry<Integer, GreKey>(new Integer(key), gre);
    }

    public List<Op> prepareGreDelete(int key) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        return prepareGreDelete(get(key));
    }

    /***
     * Constructs a list of operations to perform in a gre deletion.
     * 
     * @param entry
     *            ZK entry of the gre to delete.
     */
    public List<Op> prepareGreDelete(ZkNodeEntry<Integer, GreKey> entry) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getGreKeyPath(entry.key), -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new GRE key.
     * 
     * @return The ID of the newly created GRE.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public ZkNodeEntry<Integer, GreKey> createGreKey() throws KeeperException,
            InterruptedException {
        String path = zk.add(pathManager.getGrePath() + "/", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        int key = extractGreKeyFromPath(path);
        return new ZkNodeEntry<Integer, GreKey>(key, new GreKey());
    }

}
