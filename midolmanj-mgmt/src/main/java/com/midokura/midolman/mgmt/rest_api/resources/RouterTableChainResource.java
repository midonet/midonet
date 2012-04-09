/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for router's table chains.
 */
public class RouterTableChainResource {

    private final UUID routerId;
    private final ChainTable table;

    /**
     * Constructor
     *
     * @param routerId
     *            ID of a router.
     * @param table
     *            Chain table name.
     */
    public RouterTableChainResource(UUID routerId, ChainTable table) {
        this.routerId = routerId;
        this.table = table;
    }

    /**
     * Handler to getting a chain.
     *
     * @param name
     *            Chain name from the request.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A Chain object.
     */
    @GET
    @Path("{name}")
    @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Chain get(@PathParam("name") String name,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view chain of this router.");
        }

        ChainDao dao = daoFactory.getChainDao();
        Chain chain = dao.get(routerId, table, name);
        if (chain != null) {
            chain.setBaseUri(uriInfo.getBaseUri());
        }
        return chain;
    }

    /**
     * Handler to list chains.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Chain objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Chain> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view chains of this router.");
        }

        ChainDao dao = daoFactory.getChainDao();
        List<Chain> chains = dao.list(routerId, table);
        if (chains != null) {
            for (UriResource resource : chains) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return chains;
    }
}