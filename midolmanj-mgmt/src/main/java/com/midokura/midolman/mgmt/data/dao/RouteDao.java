/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for routes.
 */
public interface RouteDao extends GenericDao<Route, UUID> {

    /**
     * List routes.
     *
     * @param routerId
     *            ID of the router to get routes for.
     * @return A list of Route objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Route> findByRouter(UUID routerId) throws StateAccessException;

}
