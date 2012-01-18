/*
 * @(#)PortBgpResource        1.6 12/1/11
 *
 * Copyright 2012 Midokura KK
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for port's BGP.
 */
public class PortBgpResource {

    private final UUID portId;

    private final static Logger log = LoggerFactory
            .getLogger(PortBgpResource.class);

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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_BGP_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Bgp bgp, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        BgpDao dao = daoFactory.getBgpDao();
        bgp.setPortId(portId);
        UUID id = null;
        try {
            if (!authorizer.portAuthorized(context, AuthAction.WRITE, portId)) {
                throw new UnauthorizedException(
                        "Not authorized to add BGP to this port.");
            }
            id = dao.create(bgp);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }

        return Response.created(ResourceUriBuilder.getBgp(uriInfo.getBaseUri(), id))
                .build();
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A list of BGP objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_BGP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Bgp> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        BgpDao dao = daoFactory.getBgpDao();
        List<Bgp> bgps = null;
        try {
            if (!authorizer.portAuthorized(context, AuthAction.READ, portId)) {
                throw new UnauthorizedException(
                        "Not authorized to view these BGPs.");
            }
            bgps = dao.list(portId);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        for (UriResource resource : bgps) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return bgps;
    }
}