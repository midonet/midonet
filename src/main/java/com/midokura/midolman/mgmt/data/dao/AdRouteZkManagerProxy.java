/*
 * @(#)AdRouteZkManagerProxy        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;

/**
 * Data access class for advertising route.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class AdRouteZkManagerProxy extends ZkMgmtManager implements
        OwnerQueryable {

    private AdRouteZkManager zkManager = null;

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public AdRouteZkManagerProxy(Directory zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new AdRouteZkManager(zk, basePath);
    }

    /**
     * Add a JAXB object the ZK directories.
     * 
     * @param adRoute
     *            AdRoute object to add.
     * @throws ZkStateSerializationException
     * @throws StateAccessException
     * @throws UnknownHostException
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public UUID create(AdRoute adRoute) throws UnknownHostException,
            StateAccessException, ZkStateSerializationException {
        return zkManager.create(adRoute.toConfig());
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     * 
     * @param id
     *            AdRoute UUID to fetch..
     * @throws ZkStateSerializationException
     * @throws StateAccessException
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public AdRoute get(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        // TODO: Throw NotFound exception here.
        return AdRoute.createAdRoute(id, zkManager.get(id).value);
    }

    public List<AdRoute> list(UUID bgpId) throws StateAccessException,
            ZkStateSerializationException {
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        List<ZkNodeEntry<UUID, AdRouteConfig>> entries = zkManager.list(bgpId);
        for (ZkNodeEntry<UUID, AdRouteConfig> entry : entries) {
            adRoutes.add(AdRoute.createAdRoute(entry.key, entry.value));
        }
        return adRoutes;
    }

    public void update(UUID id, AdRoute adRoute)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException(
                "Ad route update is not currently supported.");
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        // TODO: catch NoNodeException if does not exist.
        zkManager.delete(id);
    }

    @Override
    public UUID getOwner(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        AdRoute route = get(id);
        OwnerQueryable manager = new BgpZkManagerProxy(zk, pathManager
                .getBasePath(), mgmtPathManager.getBasePath());
        return manager.getOwner(route.getBgpId());
    }
}
