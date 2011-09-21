/*
 * @(#)BgpZkManager        1.6 11/09/13
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.state;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;

public class BgpZkManager extends ZkManager {

    public static final class BgpConfig {
        /*
         * The bgp is a list of BGP information dictionaries enabled on this
         * port. The keys for the dictionary are:
         * 
         * local_port: local TCP port number for BGP, as a positive integer.
         * local_as: local AS number that belongs to, as a positive integer.
         * peer_addr: IPv4 address of the peer, as a human-readable string.
         * peer_port: TCP port number at the peer, as a positive integer, as a
         * string. tcp_md5sig_key: TCP MD5 signature to authenticate a session.
         * ad_routes: A list of routes to be advertised. Each item is a list
         * [address, length] that represents a network prefix, where address is
         * an IPv4 address as a human-readable string, and length is a positive
         * integer.
         */
        public int localAS;
        public InetAddress peerAddr;
        public int peerAS;
        public UUID portId;

        public BgpConfig(UUID portId, int localAS, InetAddress peerAddr,
                int peerAS) {
            this.portId = portId;
            this.localAS = localAS;
            this.peerAddr = peerAddr;
            this.peerAS = peerAS;
        }

        // Default constructor for the Jackson deserialization.
        public BgpConfig() {
            super();
        }
    }

    /**
     * BgpZkManager constructor.
     * 
     * @param zk
     *            Zookeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public BgpZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public BgpZkManager(ZooKeeper zk, String basePath) {
        this(new ZkDirectory(zk, "", null), basePath);
    }

    public List<Op> prepareBgpCreate(ZkNodeEntry<UUID, BgpConfig> bgpNode)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {

        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getBgpPath(bgpNode.key),
                    serialize(bgpNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BgpConfig", e, BgpConfig.class);
        }
        ops.add(Op.create(pathManager.getBgpAdRoutesPath(bgpNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops
                .add(Op.create(pathManager.getPortBgpPath(bgpNode.value.portId,
                        bgpNode.key), null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareBgpDelete(ZkNodeEntry<UUID, BgpConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, IOException {
        List<Op> ops = new ArrayList<Op>();

        // Delete the advertising routes
        AdRouteZkManager adRouteManager = new AdRouteZkManager(zk, pathManager.getBasePath());
        List<ZkNodeEntry<UUID, AdRouteConfig>> adRoutes = adRouteManager
                .list(entry.key);
        for (ZkNodeEntry<UUID, AdRouteConfig> adRoute : adRoutes) {
            ops.addAll(adRouteManager.prepareAdRouteDelete(adRoute));
        }
        ops.add(Op.delete(pathManager.getBgpAdRoutesPath(entry.key), -1));

        // Delete the port bgp entry
        ops.add(Op.delete(pathManager.getPortBgpPath(entry.value.portId,
                entry.key), -1));

        // Delete the bgp
        ops.add(Op.delete(pathManager.getBgpPath(entry.key), -1));
        return ops;
    }

    public UUID create(BgpConfig bgp) throws InterruptedException,
            KeeperException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, BgpConfig> bgpNode = new ZkNodeEntry<UUID, BgpConfig>(
                id, bgp);
        zk.multi(prepareBgpCreate(bgpNode));
        return id;
    }

    public ZkNodeEntry<UUID, BgpConfig> get(UUID id, Runnable watcher)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        byte[] data = zk.get(pathManager.getBgpPath(id), watcher);
        BgpConfig config = null;
        try {
            config = deserialize(data, BgpConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize bgp " + id + " to BgpConfig", e,
                    BgpConfig.class);
        }
        return new ZkNodeEntry<UUID, BgpConfig>(id, config);
    }

    public ZkNodeEntry<UUID, BgpConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        return get(id, null);
    }

    public List<ZkNodeEntry<UUID, BgpConfig>> list(UUID portId, Runnable watcher)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, BgpConfig>> result = new ArrayList<ZkNodeEntry<UUID, BgpConfig>>();
        Set<String> bgpIds = zk.getChildren(pathManager.getPortBgpPath(portId),
                watcher);
        for (String bgpId : bgpIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(bgpId)));
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, BgpConfig>> list(UUID portId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return list(portId, null);
    }

    public void update(ZkNodeEntry<UUID, BgpConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        // Update any version for now.
        try {
            zk
                    .update(pathManager.getBgpPath(entry.key),
                            serialize(entry.value));
        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize bgp "
                    + entry.key + " to BgpConfig", e, BgpConfig.class);
        }
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            ZkStateSerializationException, IOException {
        this.zk.multi(prepareBgpDelete(get(id)));
    }
}
