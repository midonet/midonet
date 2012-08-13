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
 */

public interface BgpDao extends GenericDao <Bgp, UUID>{

    /**
     * Get the BGP of the AdRoute with the given ID.
     *
     * @param adRouteId
     *            The ID of the AdRoute to find the BGP object for.
     * @return Bgp object found.
     * @throws StateAccessException
     *             Data access error.
     */
    Bgp findByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * List BGPs.
     *
     * @return A list of BGP objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Bgp> findByPort(UUID portId) throws StateAccessException;

}