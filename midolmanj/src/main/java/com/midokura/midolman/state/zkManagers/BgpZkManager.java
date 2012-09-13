/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midonet.cluster.data.BGP;

public class BgpZkManager extends ZkManager {

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

    public List<Op> prepareBgpCreate(UUID id, BGP config)
            throws ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getBgpPath(id),
                serializer.serialize(config.getData()), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getBgpAdRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getPortBgpPath(config.getPortId(), id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareBgpDelete(UUID id) throws StateAccessException {
        return prepareBgpDelete(id, getBGP(id));
    }

    public List<Op> prepareBgpDelete(UUID id, BGP config)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Delete the advertising routes
        AdRouteZkManager adRouteManager = new AdRouteZkManager(zk,
                paths.getBasePath());
        List<UUID> adRouteIds = adRouteManager.list(id);
        for (UUID adRouteId : adRouteIds) {
            ops.addAll(adRouteManager.prepareAdRouteDelete(adRouteId));
        }
        ops.add(Op.delete(paths.getBgpAdRoutesPath(id), -1));

        // Delete the port bgp entry
        ops.add(Op.delete(paths.getPortBgpPath(config.getPortId(), id),
            -1));

        // Delete the bgp
        ops.add(Op.delete(paths.getBgpPath(id), -1));
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

    public UUID create(BGP bgp) throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareBgpCreate(id, bgp));
        return id;
    }

    public BGP getBGP(UUID id, Runnable watcher) throws
        StateAccessException {
        byte[] data = get(paths.getBgpPath(id), watcher);
        return new BGP(id, serializer.deserialize(data, BGP.Data.class));
    }

    public BGP getBGP(UUID id) throws StateAccessException {
        return getBGP(id, null);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return exists(paths.getBgpPath(id));
    }

    public List<UUID> list(UUID portId, Runnable watcher)
            throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> bgpIds = getChildren(paths.getPortBgpPath(portId),
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

    public void update(UUID id, BGP config) throws StateAccessException {
        byte[] data = serializer.serialize(config);
        update(paths.getBgpPath(id), data);
    }

    public void delete(UUID id) throws StateAccessException {
        multi(prepareBgpDelete(id));
    }
}
