/*
 * @(#)RouterTableResource        1.6 12/1/11
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.UUID;

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Sub-resource class for router's chain tables.
 */
public class RouterTableResource {

    private final UUID routerId;

    /**
     * Constructor
     *
     * @param routerId
     *            ID of a router.
     */
    public RouterTableResource(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * Chain resource locator for router.
     *
     * @param id
     *            Chain ID from the request.
     * @returns RouterTableChainResource object to handle sub-resource requests.
     */
    @Path("/{name}" + ResourceUriBuilder.CHAINS)
    public RouterTableChainResource getChainTableResource(
            @PathParam("name") ChainTable name) {
        return new RouterTableChainResource(routerId, name);
    }
}