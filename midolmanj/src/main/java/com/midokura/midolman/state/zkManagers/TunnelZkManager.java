/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;


/**
 * Class to manage the Tunnel ZooKeeper data.
 */
public class TunnelZkManager extends ZkManager {
    private final static Logger log =
         LoggerFactory.getLogger(TunnelZkManager.class);

    public static class TunnelKey {

        public TunnelKey() {
            super();
        }

        public TunnelKey(UUID ownerId) {
            super();
            this.ownerId = ownerId;
        }

        public UUID ownerId;
    }

    /**
     * Initializes a TunnelZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public TunnelZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    private int extractTunnelKeyFromPath(String path) {
        int idx = path.lastIndexOf('/');
        return Integer.parseInt(path.substring(idx + 1));
    }

    /**
     * Constructs a list of operations to perform in a tunnelKey update.
     *
     * @param tunnelKey
     *            TunnelKey ZooKeeper entry to update.
     * @return A list of Op objects representing the operations to perform.
     * @throws com.midokura.midolman.state.ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareTunnelUpdate(int key, TunnelKey tunnelKey)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.setData(paths.getTunnelKeyPath(key),
                serializer.serialize(tunnelKey), -1));
        return ops;
    }

    public TunnelKey get(int key)
            throws StateAccessException {
        TunnelKey tunnelKey = null;
        byte[] data = get(paths.getTunnelKeyPath(key));
        if (data != null) {
            tunnelKey = serializer.deserialize(data, TunnelKey.class);
        }
        return tunnelKey;
    }

    /***
     * Constructs a list of operations to perform in a tunnel deletion.
     *
     * @param tunnelKey
     *            ZK entry of the tunnel to delete.
     */
    public List<Op> prepareTunnelDelete(int tunnelKey) {
        String path = paths.getTunnelKeyPath(tunnelKey);
        log.debug("Preparing to delete: " + path);
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(path, -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new Tunnel key.
     *
     * @return The ID of the newly created Tunnel.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public int createTunnelKey()
            throws StateAccessException {
        String path = addPersistentSequential(paths.getTunnelPath(), null);
        int key = extractTunnelKeyFromPath(path);
        // We don't use Zero as a valid Tunnel key because our PacketIn method
        // uses that value to denote "The Tunnel ID wasn't set".
        if (0 == key) {
            path = addPersistentSequential(paths.getTunnelPath(), null);
            key = extractTunnelKeyFromPath(path);
        }
        return key;
    }

}
