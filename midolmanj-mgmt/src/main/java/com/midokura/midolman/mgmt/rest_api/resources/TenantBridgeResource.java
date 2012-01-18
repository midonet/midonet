/*
 * @(#)TenantBridgeResource        1.6 12/1/11
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
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for tenant's virtual switch.
 */
public class TenantBridgeResource {

    private final static Logger log = LoggerFactory
            .getLogger(TenantBridgeResource.class);
    private String tenantId = null;

    /**
     * Constructor.
     *
     * @param tenantId
     *            UUID of a tenant.
     */
    public TenantBridgeResource(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Handler for creating a tenant bridge.
     *
     * @param bridge
     *            Bridge object.
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
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Bridge bridge, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        BridgeDao dao = daoFactory.getBridgeDao();
        bridge.setTenantId(tenantId);
        UUID id = null;
        try {
            if (!authorizer.tenantAuthorized(context, AuthAction.WRITE,
                    tenantId)) {
                throw new UnauthorizedException(
                        "Not authorized to add bridge to this tenant.");
            }
            id = dao.create(bridge);
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

        return Response.created(ResourceUriBuilder.getBridge(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to list tenant bridges.
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
     * @return A list of Bridge objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Bridge> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        BridgeDao dao = daoFactory.getBridgeDao();
        List<Bridge> bridges = null;
        try {
            if (!authorizer
                    .tenantAuthorized(context, AuthAction.READ, tenantId)) {
                throw new UnauthorizedException(
                        "Not authorized to view bridges of this tenant.");
            }
            bridges = dao.list(tenantId);
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

        for (UriResource resource : bridges) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return bridges;
    }
}