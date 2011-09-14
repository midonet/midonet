/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.RouterDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.rest_api.v1.resources.PortResource.RouterPortResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouteResource.RouterRouteResource;
 
/**
 * Root resource class for Virtual Router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/routers")
public class RouterResource extends RestResource {
    /*
     * Implements REST API endpoints for routers.
     */

    private final static Logger log = LoggerFactory.getLogger(
            RouterResource.class);

    /**
     * Port resource locator for routers
     */
    @Path("/{id}/ports")
    public RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return new RouterPortResource(zookeeperConn, id);
    }    

    /**
     * Route resource locator for routers
     */
    @Path("/{id}/routes")
    public RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return new RouterRouteResource(zookeeperConn, id);
    }    

    /**
     * Get the Router with the given ID.
     * @param id  Router UUID.
     * @return  Router object.
     * @throws Exception 
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Router get(@PathParam("id") UUID id) {
        // Get a router for the given ID.
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
        Router router = null;
        try {
            router = dao.get(id);
        } catch (Exception ex) {
            log.error("Error getting router", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        return router;
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, Router router){
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
        try {
            dao.update(id, router);
        } catch (Exception ex) {
            log.error("Error updating router", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        
        return Response.ok().build();
    }
    
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
        try {
            dao.delete(id);
        } catch (Exception ex) {
            log.error("Error deleting router", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
    }
    
    /**
     * Sub-resource class for tenant's virtual router.
     */
    public static class TenantRouterResource extends RestResource {
        
        private UUID tenantId = null;
        
        /**
         * Default constructor.
         * 
         * @param   zkConn  Zookeeper connection string.
         * @param   tenantId  UUID of a tenant.
         */
        public TenantRouterResource(String zkConn, UUID tenantId) {
            this.zookeeperConn = zkConn;
            this.tenantId = tenantId;        
        }
        
        /**
         * Return a list of routers.
         * 
         * @return  A list of Router objects.
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Router[] list() {
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
            Router[] routers = null;
            try {
                routers = dao.list(tenantId);
            } catch (Exception ex) {
                log.error("Error getting routers", ex);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());           
            }
            return routers;
        }
        
        /**
         * Handler for create router API call.
         * 
         * @param   router  Router object mapped to the request input.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Router router, @Context UriInfo uriInfo) {
            router.setTenantId(tenantId);
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
            UUID id = null;
            try {
                id = dao.create(router);
            } catch (Exception ex) {
                log.error("Error creating router", ex);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());
            }

            URI uri = uriInfo.getBaseUriBuilder()
                .path("routers/" + id).build();            
            return Response.created(uri).build();
        }        
    }    
}
