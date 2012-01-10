/*
 * @(#)AdRouteDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for ad routes.
 *
 * @version 1.6 29 Nov 2011
 * @author Yoshi Tamura
 */
public interface AdRouteDao {

    /**
     * @param adRoute
     *            AdRoute object to create.
     * @return New ID of the object created.
     * @throws StateAccessException
     *             Data access error.
     */
    UUID create(AdRoute adRoute) throws StateAccessException;

    /**
     * Delete a route.
     *
     * @param id
     *            ID of the route to delete.
     * @throws StateAccessException
     *             Data access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * @param id
     *            ID of the AdRoute to retrieve.
     * @return AdRoute object.
     * @throws StateAccessException
     *             Data access error.
     */
    AdRoute get(UUID id) throws StateAccessException;

    /**
     * @param bgpId
     *            ID of the BGP to get the list of AdRoute objects for.
     * @return A list of AdRoute objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<AdRoute> list(UUID bgpId) throws StateAccessException;
}
