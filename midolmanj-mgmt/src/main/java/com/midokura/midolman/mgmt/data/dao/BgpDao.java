/*
 * @(#)BgpDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for BGP.
 *
 * @version 1.6 29 Nov 2011
 * @author Yoshi Tamura
 */

public interface BgpDao {

    /**
     * Create a BGP.
     *
     * @param BGP
     *            BGP to create.
     * @return BGP ID.
     * @throws StateAccessException
     *             Data Access error.
     */
    UUID create(Bgp bgp) throws StateAccessException;

    /**
     * Delete a BGP.
     *
     * @param id
     *            ID of the BGP to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get a BGP.
     *
     * @param id
     *            ID of the BGP to get.
     * @return Route object.
     * @throws StateAccessException
     *             Data Access error.
     */
    Bgp get(UUID id) throws StateAccessException;

    /**
     * Get the BGP of the AdRoute with the given ID.
     *
     * @param adRouteId
     *            The ID of the AdRoute to find the BGP object for.
     * @return Bgp object found.
     * @throws StateAccessException
     *             Data access error.
     */
    Bgp getByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * List BGPs.
     *
     * @return A list of BGP objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Bgp> list(UUID portId) throws StateAccessException;

}