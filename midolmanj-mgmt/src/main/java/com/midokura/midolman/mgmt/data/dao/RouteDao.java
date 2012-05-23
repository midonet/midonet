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
public interface RouteDao {

    /**
     * Create a route.
     *
     * @param route
     *            Route to create.
     * @return Route ID.
     * @throws StateAccessException
     *             Data Access error.
     */
    UUID create(Route route) throws StateAccessException;

    /**
     * Delete a route.
     *
     * @param id
     *            ID of the route to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get a route.
     *
     * @param id
     *            ID of the route to get.
     * @return Route object.
     * @throws StateAccessException
     *             Data Access error.
     */
    Route get(UUID id) throws StateAccessException;

    /**
     * List routes.
     *
     * @param routerId
     *            ID of the router to get routes for.
     * @return A list of Route objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Route> list(UUID routerId) throws StateAccessException;

}
