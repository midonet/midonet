/*
 * @(#)TenantResource        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthManager;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.dao.TenantZkManager;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.rest_api.v1.resources.BridgeResource.TenantBridgeResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouterResource.TenantRouterResource;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for tenants.
 * 
 * @version 1.6 07 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/tenants")
public class TenantResource extends RestResource {
    /*
     * Implements REST API endpoints for tenants.
     */

    private final static Logger log = LoggerFactory
            .getLogger(TenantResource.class);

    /**
     * Router resource locator for tenants
     */
    @Path("/{id}/routers")
    public TenantRouterResource getRouterResource(@PathParam("id") UUID id) {
        return new TenantRouterResource(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot, id);
    }

    /**
     * Bridge resource locator for tenants
     */
    @Path("/{id}/bridges")
    public TenantBridgeResource getBridgeResource(@PathParam("id") UUID id) {
        return new TenantBridgeResource(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot, id);
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context) throws StateAccessException,
            UnauthorizedException {

        if (!AuthManager.isAdmin(context)) {
            throw new UnauthorizedException(
                    "Must be an admin to delete a tenant.");
        }

        TenantZkManager dao = new TenantZkManager(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot);
        try {
            dao.delete(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    /**
     * Handler for create tenant API call.
     * 
     * @param tenant
     *            Tenant object.
     * @throws StateAccessException
     * @throws UnauthorizedException
     * @throws Exception
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(Tenant tenant, @Context SecurityContext context)
            throws StateAccessException, UnauthorizedException {

        if (!AuthManager.isAdmin(context)) {
            throw new UnauthorizedException("Must be admin to create tenant.");
        }

        TenantZkManager dao = new TenantZkManager(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot);
        UUID id = null;
        try {
            id = dao.create(tenant.getId());
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }

        return Response.created(URI.create("/" + id)).build();
    }
}
