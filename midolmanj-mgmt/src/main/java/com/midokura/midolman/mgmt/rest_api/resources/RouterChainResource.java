/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for router's table chains.
 */
public class RouterChainResource {

    private final UUID routerId;

    /**
     * Constructor
     *
     * @param routerId
     *            ID of a router.
     */
    public RouterChainResource(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * Handler for creating a router chain.
     *
     * @param chain
     *            Chain object.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Chain chain, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.WRITE, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add chain to this router.");
        }

        ChainDao dao = daoFactory.getChainDao();
        chain.setRouterId(routerId);
        UUID id = dao.create(chain);
        return Response.created(
                ResourceUriBuilder.getChain(uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler to getting a collection of chains.
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
                    "Not authorized to view these chains.");
        }

        ChainDao dao = daoFactory.getChainDao();
        List<Chain> chains = dao.list(routerId);
        if (chains != null) {
            for (UriResource resource : chains) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return chains;
    }
}