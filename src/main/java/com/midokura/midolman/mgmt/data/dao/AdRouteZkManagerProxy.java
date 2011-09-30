/*
 * @(#)AdRouteZkManagerProxy        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;

/**
 * Data access class for advertising route.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class AdRouteZkManagerProxy extends ZkMgmtManager {

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
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public UUID create(AdRoute adRoute) throws Exception {
        return zkManager.create(adRoute.toConfig());
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     * 
     * @param id
     *            AdRoute UUID to fetch..
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public AdRoute get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return AdRoute.createAdRoute(id, zkManager.get(id).value);
    }

    public List<AdRoute> list(UUID bgpId) throws Exception {
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        List<ZkNodeEntry<UUID, AdRouteConfig>> entries = zkManager.list(bgpId);
        for (ZkNodeEntry<UUID, AdRouteConfig> entry : entries) {
            adRoutes.add(AdRoute.createAdRoute(entry.key, entry.value));
        }
        return adRoutes;
    }

    public void update(UUID id, AdRoute adRoute) throws Exception {
        throw new UnsupportedOperationException(
                "Ad route update is not currently supported.");
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        zkManager.delete(id);
    }

    public UUID getTenant(UUID id) throws Exception {
        AdRoute route = get(id);
        BgpZkManagerProxy manager = new BgpZkManagerProxy(zk, pathManager
                .getBasePath(), mgmtPathManager.getBasePath());
        return manager.getTenant(route.getBgpId());
    }
}
