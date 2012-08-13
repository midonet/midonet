/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.zkManagers.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for routes.
 */
public class RouteDaoImpl implements RouteDao {

    private final RouteZkManager dataAccessor;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            Route data accessor.
     */
    public RouteDaoImpl(RouteZkManager dataAccessor) {
        this.dataAccessor = dataAccessor;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouteDao#create(com.midokura.midolman
     * .mgmt.data.dto.Route)
     */
    @Override
    public UUID create(Route route) throws StateAccessException {
        return dataAccessor.create(route.toZkRoute());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouteDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        dataAccessor.delete(id);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouteDao#get(java.util.UUID)
     */
    @Override
    public Route get(UUID id) throws StateAccessException {
        try {
            return new Route(id, dataAccessor.get(id));
        } catch (NoStatePathException e) {
            return null;
        }
    }

    @Override
    public void update(Route obj)
            throws StateAccessException, InvalidDataOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Route> findByRouter(UUID routerId) throws StateAccessException {
        List<Route> routeList = new ArrayList<Route>();
        List<UUID> routeIds = dataAccessor.list(routerId);
        for (UUID routeId : routeIds) {
            Route route = new Route(routeId, dataAccessor.get(routeId));
            routeList.add(route);
        }
        return routeList;
    }
}
