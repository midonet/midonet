/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state.zkManagers;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.zkManagers.AdRouteZkManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

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
        // TODO: Why is this an InetAddress instead of an IntIPv4?
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

    public List<Op> prepareBgpCreate(UUID id, BgpConfig config)
            throws ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getBgpPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getBgpAdRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(pathManager.getPortBgpPath(config.portId, id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareBgpDelete(UUID id) throws StateAccessException {
        return prepareBgpDelete(id, get(id));
    }

    public List<Op> prepareBgpDelete(UUID id, BgpConfig config)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Delete the advertising routes
        AdRouteZkManager adRouteManager = new AdRouteZkManager(zk,
                pathManager.getBasePath());
        List<UUID> adRouteIds = adRouteManager.list(id);
        for (UUID adRouteId : adRouteIds) {
            ops.addAll(adRouteManager.prepareAdRouteDelete(adRouteId));
        }
        ops.add(Op.delete(pathManager.getBgpAdRoutesPath(id), -1));

        // Delete the port bgp entry
        ops.add(Op.delete(pathManager.getPortBgpPath(config.portId, id), -1));

        // Delete the bgp
        ops.add(Op.delete(pathManager.getBgpPath(id), -1));
        return ops;
    }

    public List<Op> preparePortDelete(UUID portId) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        List<UUID> bgpIdList = list(portId);
        for (UUID bgpId : bgpIdList) {
            ops.addAll(prepareBgpDelete(bgpId));
        }

        return ops;
    }

    public UUID create(BgpConfig bgp) throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareBgpCreate(id, bgp));
        return id;
    }

    public BgpConfig get(UUID id, Runnable watcher) throws StateAccessException {
        byte[] data = get(pathManager.getBgpPath(id), watcher);
        return serializer.deserialize(data, BgpConfig.class);
    }

    public BgpConfig get(UUID id) throws StateAccessException {
        return get(id, null);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return exists(pathManager.getBgpPath(id));
    }

    public List<UUID> list(UUID portId, Runnable watcher)
            throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> bgpIds = getChildren(pathManager.getPortBgpPath(portId),
                watcher);
        for (String bgpId : bgpIds) {
            // For now, get each one.
            result.add(UUID.fromString(bgpId));
        }
        return result;
    }

    public List<UUID> list(UUID portId) throws StateAccessException {
        return list(portId, null);
    }

    public void update(UUID id, BgpConfig config) throws StateAccessException {
        byte[] data = serializer.serialize(config);
        update(pathManager.getBgpPath(id), data);
    }

    public void delete(UUID id) throws StateAccessException {
        multi(prepareBgpDelete(id));
    }
}
