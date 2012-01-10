/*
 * @(#)AdRouteZkManagerProxy        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for advertising route.
 *
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class AdRouteZkManagerProxy extends ZkMgmtManager implements AdRouteDao,
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
     * @throws DataAccessException
     */
    @Override
    public UUID create(AdRoute adRoute) throws StateAccessException {
        return zkManager.create(adRoute.toConfig());
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     *
     * @param id
     *            AdRoute UUID to fetch..
     * @throws DataAccessException
     */
    @Override
    public AdRoute get(UUID id) throws StateAccessException {
        return new AdRoute(id, zkManager.get(id).value);
    }

    @Override
    public List<AdRoute> list(UUID bgpId) throws StateAccessException {
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        List<ZkNodeEntry<UUID, AdRouteConfig>> entries = null;
        entries = zkManager.list(bgpId);
        for (ZkNodeEntry<UUID, AdRouteConfig> entry : entries) {
            adRoutes.add(new AdRoute(entry.key, entry.value));
        }
        return adRoutes;
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        zkManager.delete(id);
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        AdRoute route = get(id);
        OwnerQueryable manager = new BgpZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        return manager.getOwner(route.getBgpId());
    }
}
