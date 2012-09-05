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
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
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

    public List<Op> prepareAdRouteCreate(UUID id, AdRouteConfig config)
            throws ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getAdRoutePath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(pathManager.getBgpAdRoutePath(config.bgpId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareAdRouteDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareAdRouteDelete(id, get(id));
    }

    public List<Op> prepareAdRouteDelete(UUID id, AdRouteConfig config)
            throws ZkStateSerializationException {
        // Delete the advertising route
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getAdRoutePath(id), -1));
        ops.add(Op.delete(pathManager.getBgpAdRoutePath(config.bgpId, id), -1));
        return ops;
    }

    public UUID create(AdRouteConfig adRoute) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        multi(prepareAdRouteCreate(id, adRoute));
        return id;
    }

    public AdRouteConfig get(UUID id, Runnable watcher)
            throws StateAccessException {
        byte[] data = get(pathManager.getAdRoutePath(id), watcher);
        return serializer.deserialize(data, AdRouteConfig.class);
    }

    public AdRouteConfig get(UUID id) throws StateAccessException {
        return this.get(id, null);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return exists(pathManager.getAdRoutePath(id));
    }

    public List<UUID> list(UUID bgpId, Runnable watcher)
            throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> adRouteIds = getChildren(
                pathManager.getBgpAdRoutesPath(bgpId), watcher);
        for (String adRouteId : adRouteIds) {
            // For now, get each one.
            result.add(UUID.fromString(adRouteId));
        }
        return result;
    }

    public List<UUID> list(UUID bgpId) throws StateAccessException {
        return this.list(bgpId, null);
    }

    public void update(UUID id, AdRouteConfig config)
            throws StateAccessException {
        byte[] data = serializer.serialize(config);
        update(pathManager.getAdRoutePath(id), data);
    }

    public void delete(UUID id) throws StateAccessException {
        multi(prepareAdRouteDelete(id));
    }
}
