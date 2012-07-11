/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for advertising route.
 *
 * @author Yoshi Tamura
 */
public class AdRouteDaoImpl implements AdRouteDao {

    private final AdRouteZkManager dataAccessor;

    /**
     * Constructor
     *
     * @param dataAccessor
     *            　　AdRoute data accessor.
     */
    public AdRouteDaoImpl(AdRouteZkManager dataAccessor) {
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
        try {
            return new AdRoute(id, dataAccessor.get(id));
        } catch (NoStatePathException e) {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.AdRouteDao#list(java.util.UUID)
     */
    @Override
    public List<AdRoute> list(UUID bgpId) throws StateAccessException {
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        List<UUID> ids = dataAccessor.list(bgpId);
        for (UUID id : ids) {
            adRoutes.add(new AdRoute(id, dataAccessor.get(id)));
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
