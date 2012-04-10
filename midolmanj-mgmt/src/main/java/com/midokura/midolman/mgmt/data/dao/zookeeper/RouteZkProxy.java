/*
 * @(#)RouteZkProxy        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for routes.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouteZkProxy implements RouteDao {

    private final RouteZkManager dataAccessor;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            Route data accessor.
     */
    public RouteZkProxy(RouteZkManager dataAccessor) {
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
            return new Route(id, dataAccessor.get(id).value);
        } catch (NoStatePathException e) {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouteDao#list(java.util.UUID)
     */
    @Override
    public List<Route> list(UUID routerId) throws StateAccessException {
        List<Route> routeList = new ArrayList<Route>();
        List<ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route>> routes = dataAccessor
                .list(routerId);
        for (ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route> entry : routes) {
            Route route = new Route(entry.key, entry.value);
            route.setId(entry.key);
            routeList.add(route);
        }
        return routeList;
    }
}
