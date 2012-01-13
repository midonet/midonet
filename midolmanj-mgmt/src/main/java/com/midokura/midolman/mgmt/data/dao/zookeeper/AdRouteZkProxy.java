/*
 * @(#)AdRouteZkProxy        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for advertising route.
 *
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class AdRouteZkProxy implements AdRouteDao {

    private final AdRouteZkManager dataAccessor;

    /**
     * Constructor
     *
     * @param dataAccessor
     *            　　AdRoute data accessor.
     */
    public AdRouteZkProxy(AdRouteZkManager dataAccessor) {
        this.dataAccessor = dataAccessor;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.AdRouteDao#create(com.midokura.midolman
     * .mgmt.data.dto.AdRoute)
     */
    @Override
    public UUID create(AdRoute adRoute) throws StateAccessException {
        return dataAccessor.create(adRoute.toConfig());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.AdRouteDao#get(java.util.UUID)
     */
    @Override
    public AdRoute get(UUID id) throws StateAccessException {
        return new AdRoute(id, dataAccessor.get(id).value);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.AdRouteDao#list(java.util.UUID)
     */
    @Override
    public List<AdRoute> list(UUID bgpId) throws StateAccessException {
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        List<ZkNodeEntry<UUID, AdRouteConfig>> entries = null;
        entries = dataAccessor.list(bgpId);
        for (ZkNodeEntry<UUID, AdRouteConfig> entry : entries) {
            adRoutes.add(new AdRoute(entry.key, entry.value));
        }
        return adRoutes;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.AdRouteDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        dataAccessor.delete(id);
    }
}
