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
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for port's BGP.
 */
public class PortBgpResource {

    private final UUID portId;

    /**
     * Constructor
     *
     * @param portId
     *            ID of a port.
     */
    public PortBgpResource(UUID portId) {
        this.portId = portId;
    }

    /**
     * Handler for creating BGP.
     *
     * @param chain
     *            BGP object.
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
    @Consumes({ VendorMediaType.APPLICATION_BGP_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Bgp bgp, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.portAuthorized(context, AuthAction.WRITE, portId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add BGP to this port.");
        }
        BgpDao dao = daoFactory.getBgpDao();
        bgp.setPortId(portId);
        UUID id = dao.create(bgp);
        return Response.created(
                ResourceUriBuilder.getBgp(uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler to getting a list of BGPs.
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
     * @return A list of BGP objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_BGP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Bgp> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.portAuthorized(context, AuthAction.READ, portId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these BGPs.");
        }
        BgpDao dao = daoFactory.getBgpDao();
        List<Bgp> bgps = dao.list(portId);
        if (bgps != null) {
            for (UriResource resource : bgps) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return bgps;
    }
}