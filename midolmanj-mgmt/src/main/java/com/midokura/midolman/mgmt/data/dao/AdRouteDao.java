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
 */
public interface AdRouteDao extends GenericDao <AdRoute, UUID> {

    /**
     * @param bgpId
     *            ID of the BGP to get the list of AdRoute objects for.
     * @return A list of AdRoute objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<AdRoute> findByBgp(UUID bgpId) throws StateAccessException;

}
