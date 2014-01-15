/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;


/**
 * Class to manage the Tunnel ZooKeeper data.
 */
public class TunnelZkManager extends AbstractZkManager {
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
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public TunnelZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
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
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareTunnelUpdate(int tunnelKeyId, TunnelKey tunnelKey)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.setData(paths.getTunnelPath(tunnelKeyId),
                serializer.serialize(tunnelKey), -1));
        return ops;
    }

    public TunnelKey get(int tunnelKeyId)
            throws StateAccessException, SerializationException {
        TunnelKey tunnelKey = null;
        byte[] data = zk.get(paths.getTunnelPath(tunnelKeyId));
        if (data != null) {
            tunnelKey = serializer.deserialize(data, TunnelKey.class);
        }
        return tunnelKey;
    }

    /***
     * Constructs a list of operations to perform in a tunnel deletion.
     *
     * @param tunnelKeyId
     *            ZK entry of the tunnel to delete.
     */
    public List<Op> prepareTunnelDelete(int tunnelKeyId) {
        String path = paths.getTunnelPath(tunnelKeyId);
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
    public int createTunnelKeyId()
            throws StateAccessException {
        String path = zk.addPersistentSequential(paths.getTunnelPath(), null);
        int key = extractTunnelKeyFromPath(path);
        // We don't use Zero as a valid Tunnel key because our PacketIn method
        // uses that value to denote "The Tunnel ID wasn't set".
        if (0 == key) {
            path = zk.addPersistentSequential(paths.getTunnelPath(), null);
            key = extractTunnelKeyFromPath(path);
        }
        return key;
    }

}
