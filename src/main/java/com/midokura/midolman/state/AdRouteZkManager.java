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
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

public class AdRouteZkManager extends ZkManager {

    public static final class AdRouteConfig {

        public InetAddress nwPrefix;
        public byte prefixLength;
        public UUID bgpId;

        public AdRouteConfig(UUID bgpId, InetAddress nwPrefix, byte prefixLength) {
            this.bgpId = bgpId;
            this.nwPrefix = nwPrefix;
            this.prefixLength = prefixLength;
        }

        // Default constructor for the Jackson deserialization.
        public AdRouteConfig() {
            super();
        }
    }

    /**
     * AdRouteZkManager constructor. * @param zk Zookeeper object.
     * 
     * @param basePath
     *            Directory to set as the base.
     */
    public AdRouteZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public AdRouteZkManager(ZooKeeper zk, String basePath) {
        this(new ZkDirectory(zk, "", null), basePath);
    }

    public List<Op> prepareAdRouteCreate(
            ZkNodeEntry<UUID, AdRouteConfig> adRouteNode)
            throws ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getAdRoutePath(adRouteNode.key),
                    serialize(adRouteNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize AdRouteConfig", e, AdRouteConfig.class);
        }
        ops.add(Op.create(pathManager.getBgpAdRoutePath(
                adRouteNode.value.bgpId, adRouteNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareAdRouteDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareAdRouteDelete(get(id));
    }

    public List<Op> prepareAdRouteDelete(ZkNodeEntry<UUID, AdRouteConfig> entry)
            throws ZkStateSerializationException {
        // Delete the advertising route
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getAdRoutePath(entry.key), -1));
        ops.add(Op.delete(pathManager.getBgpAdRoutePath(entry.value.bgpId,
                entry.key), -1));
        return ops;
    }

    public UUID create(AdRouteConfig adRoute) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, AdRouteConfig> adRouteNode = new ZkNodeEntry<UUID, AdRouteConfig>(
                id, adRoute);
        multi(prepareAdRouteCreate(adRouteNode));
        return id;
    }

    public ZkNodeEntry<UUID, AdRouteConfig> get(UUID id, Runnable watcher)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(pathManager.getAdRoutePath(id), watcher);
        AdRouteConfig config = null;
        try {
            config = deserialize(data, AdRouteConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize adRoute " + id + " to AdRouteConfig",
                    e, AdRouteConfig.class);
        }
        return new ZkNodeEntry<UUID, AdRouteConfig>(id, config);
    }

    public ZkNodeEntry<UUID, AdRouteConfig> get(UUID id)
            throws StateAccessException, ZkStateSerializationException {
        return this.get(id, null);
    }

    public List<ZkNodeEntry<UUID, AdRouteConfig>> list(UUID bgpId,
            Runnable watcher) throws StateAccessException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, AdRouteConfig>> result = new ArrayList<ZkNodeEntry<UUID, AdRouteConfig>>();
        Set<String> adRouteIds = getChildren(pathManager
                .getBgpAdRoutesPath(bgpId), watcher);
        for (String adRouteId : adRouteIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(adRouteId)));
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, AdRouteConfig>> list(UUID bgpId)
            throws StateAccessException, ZkStateSerializationException {
        return this.list(bgpId, null);
    }

    public void update(ZkNodeEntry<UUID, AdRouteConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = null;
        try {
            data = serialize(entry.value);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize adRoute " + entry.key
                            + " to AdRouteConfig", e, AdRouteConfig.class);
        }
        update(pathManager.getAdRoutePath(entry.key), data);
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareAdRouteDelete(id));
    }
}
