/*
 * @(#)TenantRouterResource        1.6 12/1/11
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
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for tenant's virtual router.
 */
public class TenantRouterResource {

    private final static Logger log = LoggerFactory
            .getLogger(TenantRouterResource.class);
    private String tenantId = null;

    /**
     * Constructor
     *
     * @param tenantId
     *            ID of a tenant.
     */
    public TenantRouterResource(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Handler for creating a tenant router.
     *
     * @param router
     *            Router object.
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
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Router router, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, tenantId)) {
            throw new UnauthorizedException(
                    "Not authorized to add router to this tenant.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        router.setTenantId(tenantId);
        UUID id = null;
        try {
            id = dao.create(router);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }

        return Response.created(ResourceUriBuilder.getRouter(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to list tenant routers.
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
     * @return A list of Router objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Router> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, tenantId)) {
            throw new UnauthorizedException(
                    "Not authorized to view routers of this tenant.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        List<Router> routers = null;
        try {
            routers = dao.list(tenantId);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }

        for (UriResource resource : routers) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return routers;
    }
}