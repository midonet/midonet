/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to manage the GRE ZooKeeper data.
 */
public class GreZkManager extends ZkManager {
    private final static Logger log = LoggerFactory
            .getLogger(GreZkManager.class);

    public static class GreKey {

        public GreKey() {
            super();
        }

        public GreKey(UUID ownerId) {
            super();
            this.ownerId = ownerId;
        }

        public UUID ownerId;
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
        ops.add(Op.setData(pathManager.getGreKeyPath(gre.key),
                serializer.serialize(gre.value), -1));
        return ops;
    }

    public ZkNodeEntry<Integer, GreKey> get(int key)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(pathManager.getGreKeyPath(key));
        GreKey gre = null;
        gre = serializer.deserialize(data, GreKey.class);
        return new ZkNodeEntry<Integer, GreKey>(new Integer(key), gre);
    }

    /***
     * Constructs a list of operations to perform in a gre deletion.
     *
     * @param entry
     *            ZK entry of the gre to delete.
     */
    public List<Op> prepareGreDelete(ZkNodeEntry<Integer, GreKey> entry) {
        return prepareGreDelete(entry.key);
    }

    public List<Op> prepareGreDelete(int greKey) {
        String path = pathManager.getGreKeyPath(greKey);
        log.debug("Preparing to delete: " + path);
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(path, -1));
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
    public ZkNodeEntry<Integer, GreKey> createGreKey()
            throws StateAccessException {
        String path = addPersistentSequential(pathManager.getGrePath(), null);
        int key = extractGreKeyFromPath(path);
        // We don't use Zero as a valid GRE key because our PacketIn method
        // uses that value to denote "The Tunnel ID wasn't set".
        if (0 == key) {
            path = addPersistentSequential(pathManager.getGrePath(), null);
            key = extractGreKeyFromPath(path);
        }
        return new ZkNodeEntry<Integer, GreKey>(key, new GreKey());
    }

}
