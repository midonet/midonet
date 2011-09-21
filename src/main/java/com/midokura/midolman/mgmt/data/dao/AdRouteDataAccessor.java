/*
 * @(#)AdRouteDataAccessor        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;

/**
 * Data access class for advertising route.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class AdRouteDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public AdRouteDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private AdRouteZkManager getAdRouteZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new AdRouteZkManager(conn.getZooKeeper(), zkRoot);
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
        AdRouteZkManager manager = getAdRouteZkManager();
        return manager.create(adRoute.toConfig());
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
        AdRouteZkManager manager = getAdRouteZkManager();
        // TODO: Throw NotFound exception here.
        return AdRoute.createAdRoute(id, manager.get(id).value);
    }

    public AdRoute[] list(UUID bgpId) throws Exception {
        AdRouteZkManager manager = getAdRouteZkManager();
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        List<ZkNodeEntry<UUID, AdRouteConfig>> entries = manager.list(bgpId);
        for (ZkNodeEntry<UUID, AdRouteConfig> entry : entries) {
            adRoutes.add(AdRoute.createAdRoute(entry.key, entry.value));
        }
        return adRoutes.toArray(new AdRoute[adRoutes.size()]);
    }

    public void update(UUID id, AdRoute adRoute) throws Exception {
        // AdRouteZkManager manager = getAdRouteZkManager();
        // ZkNodeEntry<UUID, AdRouteConfig> entry = manager.get(id);
        // copyAdRoute(adRoute, entry.value);
        // manager.update(entry);
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getAdRouteZkManager().delete(id);
    }
}
